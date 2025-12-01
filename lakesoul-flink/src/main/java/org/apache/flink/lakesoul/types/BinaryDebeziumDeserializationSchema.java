// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.flink.lakesoul.types;

import com.ververica.cdc.connectors.shaded.org.apache.kafka.connect.source.SourceRecord;
import com.ververica.cdc.debezium.DebeziumDeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.util.Collector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.lakesoul.tool.LakeSoulSinkOptions;

public class BinaryDebeziumDeserializationSchema implements DebeziumDeserializationSchema<BinarySourceRecord> {

    private static final long serialVersionUID = -3248249461777452263L;
    LakeSoulRecordConvert convert;
    String basePath;
    private final boolean namingEnabled;
    private final String targetNamespace;
    private final String tableFormat;
    private final String namingCase;

    public BinaryDebeziumDeserializationSchema(LakeSoulRecordConvert convert, String basePath) {
        this.convert = convert;
        this.basePath = basePath;
        this.namingEnabled = false;
        this.targetNamespace = null;
        this.tableFormat = null;
        this.namingCase = "preserve";
    }

    public BinaryDebeziumDeserializationSchema(LakeSoulRecordConvert convert, String basePath, Configuration conf) {
        this.convert = convert;
        this.basePath = basePath;
        this.namingEnabled = conf.getBoolean(LakeSoulSinkOptions.NAMING_ENABLE);
        this.targetNamespace = conf.get(LakeSoulSinkOptions.NAMING_TARGET_NAMESPACE);
        this.tableFormat = conf.get(LakeSoulSinkOptions.NAMING_TABLE_FORMAT);
        this.namingCase = conf.get(LakeSoulSinkOptions.NAMING_CASE);
    }

    @Override
    public void deserialize(SourceRecord sourceRecord, Collector<BinarySourceRecord> collector) throws Exception {
        // BinarySourceRecord binarySourceRecord = BinarySourceRecord.fromMysqlSourceRecord(sourceRecord, this.convert, this.basePath);
        BinarySourceRecord binarySourceRecord = BinarySourceRecord.fromMysqlSourceRecord(sourceRecord, this.convert, this.basePath,
            namingEnabled, targetNamespace, tableFormat, namingCase);
        if (binarySourceRecord != null) collector.collect(binarySourceRecord);
    }

    @Override
    public TypeInformation<BinarySourceRecord> getProducedType() {
        return TypeInformation.of(new TypeHint<BinarySourceRecord>() {
        });
    }
}
