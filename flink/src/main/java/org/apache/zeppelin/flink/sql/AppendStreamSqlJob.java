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

package org.apache.zeppelin.flink.sql;

import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.scala.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.StringUtils;
import org.apache.zeppelin.flink.JobManager;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.tabledata.TableDataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AppendStreamSqlJob extends AbstractStreamSqlJob {

  private static Logger LOGGER = LoggerFactory.getLogger(UpdateStreamSqlJob.class);

  private List<Row> materializedTable = new ArrayList<>();
  private long tsWindowThreshold;

  public AppendStreamSqlJob(StreamExecutionEnvironment senv,
                            StreamTableEnvironment stEnv,
                            JobManager jobManager,
                            InterpreterContext context,
                            int defaultParallelism) {
    super(senv, stEnv, jobManager, context, defaultParallelism);
    this.tsWindowThreshold = Long.parseLong(context.getLocalProperties()
            .getOrDefault("threshold", 1000 * 60 * 60 + ""));
  }

  @Override
  protected String getType() {
    return "ts";
  }

  @Override
  protected void checkTableSchema(TableSchema schema) throws Exception {
    //    if (!(schema.getFieldDataType(0).get() instanceof TimestampType)) {
    //      throw new Exception("The first column must be TimestampType, but is " +
    //              schema.getFieldDataType(0));
    //    }
  }

  @Override
  protected void processInsert(Row row) {
    LOGGER.debug("processInsert: " + row.toString());
    materializedTable.add(row);
  }

  @Override
  protected void processDelete(Row row) {
    throw new RuntimeException("Delete operation is not expected");
  }

  @Override
  protected String buildResult() {
    StringBuilder builder = new StringBuilder();
    builder.append("%table\n");
    for (int i = 0; i < schema.getFieldCount(); ++i) {
      String field = schema.getFieldNames()[i];
      builder.append(field);
      if (i != (schema.getFieldCount() - 1)) {
        builder.append("\t");
      }
    }
    builder.append("\n");

    // sort it by the first column
    materializedTable.sort((r1, r2) -> {
      String f1 = r1.getField(0).toString();
      String f2 = r2.getField(0).toString();
      return f1.compareTo(f2);
    });

    if (materializedTable.size() != 0) {
      long maxTimestamp =
              ((java.sql.Timestamp) materializedTable.get(materializedTable.size() - 1)
                      .getField(0)).getTime();

      materializedTable = materializedTable.stream()
              .filter(row -> ((java.sql.Timestamp) row.getField(0)).getTime() >
                      maxTimestamp - tsWindowThreshold)
              .collect(Collectors.toList());

      for (Row row : materializedTable) {
        for (int i = 0; i < row.getArity(); ++i) {
          Object field = row.getField(i);
          builder.append(TableDataUtils.normalizeColumn(StringUtils.arrayAwareToString(field)));
          if (i != (row.getArity() - 1)) {
            builder.append("\t");
          }
        }
        builder.append("\n");
      }
    }
    builder.append("\n%text ");
    return builder.toString();
  }

  @Override
  protected void refresh(InterpreterContext context) {
    context.out().clear();
    try {
      jobManager.sendFlinkJobUrl(context);
      context.out.write(buildResult());
      context.out.flush();
    } catch (IOException e) {
      LOGGER.error("Fail to refresh data", e);
    }
  }
}
