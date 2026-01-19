#!/bin/bash
# 安全打包脚本 - 不包含任何测试代码
# 避免测试代码自动清理数据库

set -e

echo "=== 安全打包流程 - 不包含任何测试代码 ==="
echo ""

java --version

echo "=== 清理基础环境 ==="
echo ""
mvn clean
mvn -q -pl lakesoul-common -am clean
# 清理 Scala 增量编译缓存
rm -rf lakesoul-common/target/streams
rm -rf lakesoul-common/target/.scala_dependencies
find lakesoul-common/target -name "*.cache" -delete 2>/dev/null || true
# 清理 lakesoul-flink 的 Scala 增量编译缓存
rm -rf lakesoul-flink/target/streams
rm -rf lakesoul-flink/target/.scala_dependencies
find lakesoul-flink/target -name "*.cache" -delete 2>/dev/null || true
sleep 2
# mvn -q -pl lakesoul-io-java -am clean

# 1. 构建 lakesoul-common（跳过测试，确保 Rust 构建不包含测试代码）
echo "步骤 1/3: 构建 lakesoul-common..."
cd lakesoul-common
mvn clean install -DskipTests -Dmaven.test.skip=true
cd ..

# 2. 构建 native-io/lakesoul-io-java（跳过测试）
echo ""
echo "步骤 2/3: 构建 native-io/lakesoul-io-java..."
cd native-io/lakesoul-io-java
mvn clean install -DskipTests -Dmaven.test.skip=true
cd ../..

# 3. 构建 lakesoul-flink（跳过测试，排除测试类）
echo ""
echo "步骤 3/3: 构建 lakesoul-flink..."
cd lakesoul-flink
mvn clean package -DskipTests -Dmaven.test.skip=true
cd ..

echo ""
echo "=== 打包完成 ==="
echo ""
echo "验证步骤："
echo "1. 检查 JAR 包中不包含测试类："
echo "   jar tf lakesoul-flink/target/lakesoul-flink-*.jar | grep -i 'Test\.class' | head -20"
jar tf lakesoul-flink/target/lakesoul-flink-*.jar | grep -i 'Test\.class' | head -20
echo ""
echo "2. 检查 Rust 库不包含测试代码："
echo "   strings lakesoul-common/target/classes/liblakesoul_metadata_c.so | grep -E 'clean metadata|meta_cleanup'"
strings lakesoul-common/target/classes/liblakesoul_metadata_c.so | grep -E 'clean metadata|meta_cleanup'
echo ""