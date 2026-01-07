// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.flink.lakesoul.types;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BinarySourceRecordNamingTest {

    @Test
    public void testBuildSinkTableNameDefaultPrefix() {
        assertEquals("s_user", BinarySourceRecord.buildSinkTableName(null, "user"));
        assertEquals("s_user", BinarySourceRecord.buildSinkTableName("", "user"));
        assertEquals("s_user", BinarySourceRecord.buildSinkTableName("   ", "user"));
    }

    @Test
    public void testBuildSinkTableNameTrimAndUnderscore() {
        assertEquals("ods_user", BinarySourceRecord.buildSinkTableName("ods", "user"));
        assertEquals("ods_user", BinarySourceRecord.buildSinkTableName("ods_", "user"));
        assertEquals("ods_user", BinarySourceRecord.buildSinkTableName("  ods_  ", "user"));
    }

    @Test
    public void testBuildSinkTableNameLowercase() {
        assertEquals("ods_user", BinarySourceRecord.buildSinkTableName("ODS", "User"));
    }
}
