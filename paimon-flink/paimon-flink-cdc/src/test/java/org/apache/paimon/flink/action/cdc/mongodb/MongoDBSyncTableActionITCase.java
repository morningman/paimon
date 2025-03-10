/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc.mongodb;

import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** IT cases for {@link MongoDBSyncTableActionITCase}. */
public class MongoDBSyncTableActionITCase extends MongoDBActionITCaseBase {

    @Test
    @Timeout(60)
    public void testSchemaEvolution() throws Exception {
        runSingleTableSchemaEvolution("inventory-1");
    }

    private void runSingleTableSchemaEvolution(String sourceDir) throws Exception {
        // ---------- Write the Document into MongoDB -------------------
        String inventory = createRecordsToMongoDB(sourceDir, "table");
        Map<String, String> mongodbConfig = getBasicMongoDBConfig();
        mongodbConfig.put("database", inventory);
        mongodbConfig.put("collection", "products");
        MongoDBSyncTableAction action =
                syncTableActionBuilder(mongodbConfig)
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);

        testSchemaEvolutionImpl(inventory);
    }

    private void testSchemaEvolutionImpl(String dbName) throws Exception {
        FileStoreTable table = getFileStoreTable(tableName);
        List<String> primaryKeys = Collections.singletonList("_id");

        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"_id", "name", "description", "weight"});
        List<String> expected =
                Arrays.asList(
                        "+I[100000000000000000000101, scooter, Small 2-wheel scooter, 3.14]",
                        "+I[100000000000000000000102, car battery, 12V car battery, 8.1]",
                        "+I[100000000000000000000103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.8]");
        waitForResult(expected, table, rowType, primaryKeys);

        writeRecordsToMongoDB("inventory-2", dbName, "table");
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"_id", "name", "description", "weight"});
        expected =
                Arrays.asList(
                        "+I[100000000000000000000101, scooter, Small 2-wheel scooter, 350]",
                        "+I[100000000000000000000102, car battery, High-performance car battery, 8.1]",
                        "+I[100000000000000000000103, 12-pack drill bits, Set of 12 professional-grade drill bits, 0.8]");
        waitForResult(expected, table, rowType, primaryKeys);

        writeRecordsToMongoDB("inventory-3", dbName, "table");
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {
                            "_id", "name", "description", "weight", "hobby", "age", "address"
                        });
        expected =
                Arrays.asList(
                        "+I[100000000000000000000102, car battery, High-performance car battery, 8.1, NULL, 18, NULL]",
                        "+I[100000000000000000000103, 12-pack drill bits, Set of 12 professional-grade drill bits, 0.8, NULL, NULL, I live in Sanlitun]",
                        "+I[100000000000000000000101, scooter, Small 2-wheel scooter, 350, playing computer games, NULL, NULL]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    @Timeout(60)
    public void testSpecifiedMode() throws Exception {
        String inventory = createRecordsToMongoDB("inventory-1", "table");
        Map<String, String> mongodbConfig = getBasicMongoDBConfig();
        mongodbConfig.put("database", inventory);
        mongodbConfig.put("collection", "products");
        mongodbConfig.put("field.name", "_id,name,description");
        mongodbConfig.put("parser.path", "$._id,$.name,$.description");
        mongodbConfig.put("schema.start.mode", "specified");

        MongoDBSyncTableAction action =
                syncTableActionBuilder(mongodbConfig)
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);
        FileStoreTable table = getFileStoreTable(tableName);

        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(), DataTypes.STRING(), DataTypes.STRING()
                        },
                        new String[] {"_id", "name", "description"});
        List<String> primaryKeys = Collections.singletonList("_id");
        List<String> expected =
                Arrays.asList(
                        "+I[100000000000000000000101, scooter, Small 2-wheel scooter]",
                        "+I[100000000000000000000102, car battery, 12V car battery]",
                        "+I[100000000000000000000103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    public void testCatalogAndTableConfig() {
        MongoDBSyncTableAction action =
                syncTableActionBuilder(getBasicMongoDBConfig())
                        .withCatalogConfig(Collections.singletonMap("catalog-key", "catalog-value"))
                        .withTableConfig(Collections.singletonMap("table-key", "table-value"))
                        .build();

        assertThat(action.catalogConfig()).containsEntry("catalog-key", "catalog-value");
        assertThat(action.tableConfig())
                .containsExactlyEntriesOf(Collections.singletonMap("table-key", "table-value"));
    }

    @Test
    @Timeout(60)
    public void testComputedColumn() throws Exception {
        writeRecordsToMongoDB("test-table-1", database, "table/computedcolumn");
        Map<String, String> mongodbConfig = getBasicMongoDBConfig();
        mongodbConfig.put("database", database);
        mongodbConfig.put("collection", "test_computed_column");

        MongoDBSyncTableAction action =
                syncTableActionBuilder(mongodbConfig)
                        .withTableConfig(getBasicTableConfig())
                        .withComputedColumnArgs("_year=year(_date)")
                        .build();
        runActionWithDefaultEnv(action);
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(), DataTypes.STRING(), DataTypes.INT()
                        },
                        new String[] {"_id", "_date", "_year"});
        waitForResult(
                Collections.singletonList("+I[100000000000000000000101, 2023-03-23, 2023]"),
                getFileStoreTable(tableName),
                rowType,
                Collections.singletonList("_id"));
    }

    @Test
    @Timeout(60)
    public void testMongoDBCDCOperations() throws Exception {
        writeRecordsToMongoDB("event-insert", database, "table/event");

        Map<String, String> mongodbConfig = getBasicMongoDBConfig();
        mongodbConfig.put("database", database);
        mongodbConfig.put("collection", "event");

        MongoDBSyncTableAction action =
                syncTableActionBuilder(mongodbConfig)
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);

        FileStoreTable table = getFileStoreTable(tableName);
        List<String> primaryKeys = Collections.singletonList("_id");
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"_id", "name", "description", "weight"});

        // For the INSERT operation
        List<String> expectedInsert =
                Arrays.asList(
                        "+I[100000000000000000000101, scooter, Small 2-wheel scooter, 3.14]",
                        "+I[100000000000000000000102, car battery, 12V car battery, 8.1]",
                        "+I[100000000000000000000103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.8]");
        waitForResult(expectedInsert, table, rowType, primaryKeys);

        writeRecordsToMongoDB("event-update", database, "table/event");

        // For the UPDATE operation
        List<String> expectedUpdate =
                Arrays.asList(
                        "+I[100000000000000000000101, scooter, Updated scooter description, 4]",
                        "+I[100000000000000000000102, car battery, 12V car battery, 8.1]",
                        "+I[100000000000000000000103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.8]");
        waitForResult(expectedUpdate, table, rowType, primaryKeys);

        writeRecordsToMongoDB("event-replace", database, "table/event");

        // For the REPLACE operation
        List<String> expectedReplace =
                Arrays.asList(
                        "+I[100000000000000000000101, scooter, Updated scooter description, 4]",
                        "+I[100000000000000000000102, new car battery, New 12V car battery, 9]",
                        "+I[100000000000000000000103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.8]");
        waitForResult(expectedReplace, table, rowType, primaryKeys);

        writeRecordsToMongoDB("event-delete", database, "table/event");

        // For the DELETE operation
        List<String> expectedDelete =
                Arrays.asList(
                        "+I[100000000000000000000101, scooter, Updated scooter description, 4]",
                        "+I[100000000000000000000102, new car battery, New 12V car battery, 9]");
        waitForResult(expectedDelete, table, rowType, primaryKeys);
    }
}
