// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.spark.sql.arrow;

import com.dmetasoul.lakesoul.LakeSoulArrowReader;
import com.dmetasoul.lakesoul.lakesoul.io.NativeIOReader;
import com.dmetasoul.lakesoul.lakesoul.io.NativeIOWriter;
import com.dmetasoul.lakesoul.lakesoul.io.NativeIOWriter.FlushResult;
import com.dmetasoul.lakesoul.meta.BucketingUtils;
import com.dmetasoul.lakesoul.meta.MetaUtils;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.execution.datasources.LakeSoulFileWriter;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.lakesoul.sources.LakeSoulSQLConf;
import org.apache.spark.sql.lakesoul.utils.TableInfo;
import org.apache.spark.sql.vectorized.NativeIOOptions;
import org.apache.spark.sql.vectorized.NativeIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.collection.JavaConverters;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.dmetasoul.lakesoul.meta.DBConfig.LAKESOUL_NON_PARTITION_TABLE_PART_DESC;

public class UZSFullPartitionCompactBucketIO implements AutoCloseable, Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(UZSFullPartitionCompactBucketIO.class);
    private static final long serialVersionUID = 6319420293192325192L;

    public static String DISCARD_FILE_LIST_KEY = "discard_file";
    public static String COMPACT_DIR = "compactdir";

    private final Configuration conf;
    private final List<String> primaryKeys;
    private final List<String> rangeColumns;
    private final int maxRowGroupRows;
    private final boolean tableHashBucketNumChanged;
    private NativeIOReader nativeIOReader;
    private final Schema schema;
    private Schema partitionSchema;
    private final int hashBucketNum;
    private final NativeIOOptions nativeIOOptions;
    private final List<CompressDataFileInfo> fileInfo;
    private final String metaPartitionExpr;
    private NativeIOWriter nativeWriter;
    private LakeSoulArrowReader lakesoulArrowReader;
    private final String tablePath;
    private final int batchSize;
    private final long taskId;
    private long beginTime;

    public UZSFullPartitionCompactBucketIO(Configuration conf,
                                           List<CompressDataFileInfo> fileInfo,
                                           TableInfo tableInfo,
                                           String tablePath,
                                           String metaPartitionExpr,
                                           int tableHashBucketNum,
                                           boolean tableHashBucketNumChanged,
                                           long taskId) throws IOException {
        this.conf = conf;
        this.fileInfo = fileInfo;
        this.metaPartitionExpr = metaPartitionExpr;
        this.schema = Schema.fromJSON(tableInfo.table_schema());
        this.primaryKeys = JavaConverters.seqAsJavaList(tableInfo.hash_partition_columns().toSeq());
        this.hashBucketNum = tableHashBucketNum;
        if (StringUtils.isNotBlank(tableInfo.range_column())) {
            this.rangeColumns = Arrays.stream(tableInfo.range_column().split(",")).collect(Collectors.toList());
        } else {
            this.rangeColumns = Collections.emptyList();
        }
        if (!this.rangeColumns.isEmpty()) {
            List<Field> partitionFields = rangeColumns.stream().map(schema::findField).collect(Collectors.toList());
            this.partitionSchema = new Schema(partitionFields);
        }
        this.nativeIOOptions = NativeIOUtils.getNativeIOOptions(conf, new Path(this.fileInfo.get(0).getFilePath()));
        this.maxRowGroupRows = conf.getInt(LakeSoulSQLConf.NATIVE_IO_WRITE_MAX_ROW_GROUP_SIZE().key(),
                (int) LakeSoulSQLConf.NATIVE_IO_WRITE_MAX_ROW_GROUP_SIZE().defaultValue().get());
        this.batchSize = conf.getInt(SQLConf$.MODULE$.PARQUET_VECTORIZED_READER_BATCH_SIZE().key(), 2048);
        this.tablePath = tablePath;
        this.tableHashBucketNumChanged = tableHashBucketNumChanged;
        this.taskId = taskId;
    }

    private void initializeReader(List<CompressDataFileInfo> filePath) throws IOException {
        beginTime = System.currentTimeMillis();
        nativeIOReader = new NativeIOReader();
        for (CompressDataFileInfo path : filePath) {
            nativeIOReader.addFile(path.getFilePath());
        }
        nativeIOReader.setSchema(this.schema);
        if (this.primaryKeys != null) {
            nativeIOReader.setPrimaryKeys(this.primaryKeys);
        }
        scala.collection.immutable.Map<String, String> partitionMapFromKey = MetaUtils.getPartitionMapFromKey(metaPartitionExpr);
        for (Map.Entry<String, String> entry : JavaConverters.mapAsJavaMapConverter(partitionMapFromKey).asJava().entrySet()) {
            nativeIOReader.setDefaultColumnValue(entry.getKey(), entry.getValue());
        }
        if (this.partitionSchema != null) {
            nativeIOReader.setPartitionSchema(this.partitionSchema);
        }
        NativeIOUtils.setNativeIOOptions(this.nativeIOReader, this.nativeIOOptions);
        nativeIOReader.setBatchSize(this.batchSize);
        nativeIOReader.initializeReader();
        lakesoulArrowReader = new LakeSoulArrowReader(this.nativeIOReader, 10000);
    }

    private void initializeWriter(String outPath) throws IOException {
        nativeWriter = new NativeIOWriter(this.schema);
        nativeWriter.setRowGroupRowNumber(this.maxRowGroupRows);
        nativeWriter.setHashBucketNum(this.hashBucketNum);
        if (this.tableHashBucketNumChanged) {
            nativeWriter.setPrimaryKeys(this.primaryKeys);
            nativeWriter.setRangePartitions(rangeColumns);
            nativeWriter.useDynamicPartition(true);
            nativeWriter.withPrefix(outPath);
        } else {
            if (!this.metaPartitionExpr.equals(LAKESOUL_NON_PARTITION_TABLE_PART_DESC)) {
                nativeWriter.withPrefix(String.format("%s/%s", outPath, metaPartitionExpr.replace(",", "/")));
            } else {
                nativeWriter.withPrefix(outPath);
            }
            Option<Object> hashBucketId = BucketingUtils.getBucketId(this.fileInfo.get(0).getFilePath());
            if (hashBucketId.isEmpty()) {
                nativeWriter.setOption(LakeSoulFileWriter.HASH_BUCKET_ID_KEY(), "0");
            } else {
                nativeWriter.setOption(LakeSoulFileWriter.HASH_BUCKET_ID_KEY(), String.valueOf(hashBucketId.get()));
            }
        }
        NativeIOUtils.setNativeIOOptions(nativeWriter, this.nativeIOOptions);
        nativeWriter.initializeWriter();
    }

    private HashMap<String, List<FlushResult>> readAndWrite() throws Exception {
        VectorSchemaRoot currentVCR = null;
        try {
            while (this.lakesoulArrowReader.hasNext()) {
                currentVCR = this.lakesoulArrowReader.nextResultVectorSchemaRoot();
                nativeWriter.write(currentVCR);
                currentVCR.close();
            }
            return this.nativeWriter.flush();
        } finally {
            if (currentVCR != null) {
                currentVCR.close();
            }
        }
    }

    public HashMap<String, List<CompressDataFileInfo>> startCompactTask() throws Exception {
        HashMap<String, List<CompressDataFileInfo>> rsMap = new HashMap<>();
        if (this.fileInfo == null || this.fileInfo.isEmpty()) {
            rsMap.put(DISCARD_FILE_LIST_KEY, new ArrayList<>());
            return rsMap;
        }
        initializeReader(this.fileInfo);
        initializeWriter(String.format("%s/%s%d", this.tablePath, COMPACT_DIR, 1));
        HashMap<String, List<FlushResult>> outFile = readAndWrite();
        this.close();
        if (outFile == null || outFile.isEmpty()) {
            throw new IllegalStateException("UZS full partition compaction without output files, read file list is: " + this.fileInfo);
        }
        List<CompressDataFileInfo> resultList = new ArrayList<>();
        for (Map.Entry<String, List<FlushResult>> entry : outFile.entrySet()) {
            resultList.addAll(changeFlushFileToCompressDataFileInfo(entry.getValue()));
        }
        rsMap.put(this.metaPartitionExpr, resultList);
        rsMap.put(DISCARD_FILE_LIST_KEY, new ArrayList<>(this.fileInfo));
        return rsMap;
    }

    private List<CompressDataFileInfo> changeFlushFileToCompressDataFileInfo(List<FlushResult> flushResultList) {
        List<CompressDataFileInfo> compressDataFileInfoList = new ArrayList<>();
        flushResultList.forEach(file -> {
            String filePath = file.getFilePath();
            Path path = new Path(file.getFilePath());
            String fileExistCols = file.getFileExistCols();
            if (fileExistCols.startsWith("arrow_schema,")) {
                fileExistCols = fileExistCols.replace("arrow_schema,", "");
            }
            try {
                FileSystem fileSystem = path.getFileSystem(conf);
                FileStatus fileStatus = fileSystem.getFileStatus(path);
                compressDataFileInfoList.add(new CompressDataFileInfo(filePath, fileStatus.getLen(), fileExistCols, fileStatus.getModificationTime()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return compressDataFileInfoList;
    }

    @Override
    public void close() throws Exception {
        if (this.nativeIOReader != null) {
            this.nativeIOReader.close();
            this.nativeIOReader = null;
        }
        if (this.nativeWriter != null) {
            this.nativeWriter.close();
            this.nativeWriter = null;
        }
        if (beginTime > 0) {
            LOG.info("Task {}, time taken {}", taskId, System.currentTimeMillis() - beginTime);
            beginTime = 0;
        }
    }
}
