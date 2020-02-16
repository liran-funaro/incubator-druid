/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.benchmark.datagen.BenchmarkDataGenerator;
import org.apache.druid.benchmark.datagen.BenchmarkSchemaInfo;
import org.apache.druid.benchmark.datagen.BenchmarkSchemas;
import org.apache.druid.benchmark.query.QueryBenchmarkUtil;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.Druids;
import org.apache.druid.query.FinalizeResultsQueryRunner;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.Result;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.FilteredAggregatorFactory;
import org.apache.druid.query.aggregation.hyperloglog.HyperUniquesSerde;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.filter.BoundDimFilter;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.query.filter.InDimFilter;
import org.apache.druid.query.filter.OrDimFilter;
import org.apache.druid.query.filter.RegexDimFilter;
import org.apache.druid.query.filter.SearchQueryDimFilter;
import org.apache.druid.query.ordering.StringComparators;
import org.apache.druid.query.search.ContainsSearchQuerySpec;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.query.spec.QuerySegmentSpec;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.timeseries.TimeseriesQueryEngine;
import org.apache.druid.query.timeseries.TimeseriesQueryQueryToolChest;
import org.apache.druid.query.timeseries.TimeseriesQueryRunnerFactory;
import org.apache.druid.query.timeseries.TimeseriesResultValue;
import org.apache.druid.segment.IncrementalIndexSegment;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.IndexMergerV9;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.QueryableIndexSegment;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.incremental.IncrementalIndex;
import org.apache.druid.segment.incremental.IndexSizeExceededException;
import org.apache.druid.segment.serde.ComplexMetrics;
import org.apache.druid.segment.writeout.OffHeapMemorySegmentWriteOutMediumFactory;
import org.apache.druid.timeline.SegmentId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 10)
@Measurement(iterations = 25)
public class FilteredAggregatorBenchmark
{
  static {
    NullHandling.initializeForTests();
  }

  @Param({"75000"})
  private int rowsPerSegment;

  @Param({"basic"})
  private String schema;

  @Param({"false", "true"})
  private String vectorize;

  @Param({"true", "false"})
  private boolean descending;

  @Param({"onheap", "oak"})
  private String indexType;

  private static final Logger log = new Logger(FilteredAggregatorBenchmark.class);
  private static final int RNG_SEED = 9999;
  private static final IndexMergerV9 INDEX_MERGER_V9;
  private static final IndexIO INDEX_IO;
  public static final ObjectMapper JSON_MAPPER;

  private AggregatorFactory[] filteredMetrics;
  private File indexFile;
  private DimFilter filter;
  private QueryRunnerFactory factory;
  private BenchmarkSchemaInfo schemaInfo;
  private TimeseriesQuery query;

  static {
    JSON_MAPPER = new DefaultObjectMapper();
    INDEX_IO = new IndexIO(
        JSON_MAPPER,
        new ColumnConfig()
        {
          @Override
          public int columnCacheSizeBytes()
          {
            return 0;
          }
        }
    );
    INDEX_MERGER_V9 = new IndexMergerV9(JSON_MAPPER, INDEX_IO, OffHeapMemorySegmentWriteOutMediumFactory.instance());
  }

  @Setup
  public void setup() throws IOException
  {
    log.info("SETUP CALLED AT " + System.currentTimeMillis());

    ComplexMetrics.registerSerde("hyperUnique", new HyperUniquesSerde());

    schemaInfo = BenchmarkSchemas.SCHEMA_MAP.get(schema);

    filter = new OrDimFilter(
        Arrays.asList(
            new BoundDimFilter("dimSequential", "-1", "-1", true, true, null, null, StringComparators.ALPHANUMERIC),
            new RegexDimFilter("dimSequential", "X", null),
            new SearchQueryDimFilter("dimSequential", new ContainsSearchQuerySpec("X", false), null),
            new InDimFilter("dimSequential", Collections.singletonList("X"), null)
        )
    );
    filteredMetrics = new AggregatorFactory[1];
    filteredMetrics[0] = new FilteredAggregatorFactory(new CountAggregatorFactory("rows"), filter);

    factory = new TimeseriesQueryRunnerFactory(
        new TimeseriesQueryQueryToolChest(
            QueryBenchmarkUtil.noopIntervalChunkingQueryRunnerDecorator()
        ),
        new TimeseriesQueryEngine(),
        QueryBenchmarkUtil.NOOP_QUERYWATCHER
    );

    BenchmarkSchemaInfo basicSchema = BenchmarkSchemas.SCHEMA_MAP.get("basic");
    QuerySegmentSpec intervalSpec = new MultipleIntervalSegmentSpec(Collections.singletonList(basicSchema.getDataInterval()));
    List<AggregatorFactory> queryAggs = new ArrayList<>();
    queryAggs.add(filteredMetrics[0]);

    query = Druids.newTimeseriesQueryBuilder()
                  .dataSource("blah")
                  .granularity(Granularities.ALL)
                  .intervals(intervalSpec)
                  .aggregators(queryAggs)
                  .descending(descending)
                  .build();
  }

  @State(Scope.Benchmark)
  public static class IncrementalIndexState
  {
    IncrementalIndex incIndex;

    @Setup
    public void setup(FilteredAggregatorBenchmark global) throws IndexSizeExceededException
    {
      BenchmarkDataGenerator gen = new BenchmarkDataGenerator(
          global.schemaInfo.getColumnSchemas(),
          RNG_SEED,
          global.schemaInfo.getDataInterval(),
          global.rowsPerSegment
      );

      incIndex = global.makeIncIndex(global.schemaInfo.getAggsArray());

      for (int j = 0; j < global.rowsPerSegment; j++) {
        InputRow row = gen.nextRow();
        // if (j % 10000 == 0) {
        //   log.info(j + " rows generated.");
        // }
        incIndex.add(row);
      }
    }

    @TearDown
    public void tearDown()
    {
      incIndex.close();
    }
  }

  @State(Scope.Benchmark)
  public static class FilteredIncrementalIndexState
  {
    IncrementalIndex incIndex;
    private List<InputRow> inputRows;

    @Setup
    public void setup(FilteredAggregatorBenchmark global) throws IndexSizeExceededException
    {
      BenchmarkDataGenerator gen = new BenchmarkDataGenerator(
          global.schemaInfo.getColumnSchemas(),
          RNG_SEED,
          global.schemaInfo.getDataInterval(),
          global.rowsPerSegment
      );

      incIndex = global.makeIncIndex(global.filteredMetrics);

      for (int j = 0; j < global.rowsPerSegment; j++) {
        InputRow row = gen.nextRow();
        // if (j % 10000 == 0) {
        //   log.info(j + " rows generated.");
        // }
        inputRows.add(row);
      }
    }

    @TearDown
    public void tearDown()
    {
      incIndex.close();
    }
  }

  @State(Scope.Benchmark)
  public static class QueryableIndexState
  {
    private File qIndexFile;
    private QueryableIndex qIndex;

    @Setup
    public void setup(FilteredAggregatorBenchmark global) throws IOException
    {
      BenchmarkDataGenerator gen = new BenchmarkDataGenerator(
          global.schemaInfo.getColumnSchemas(),
          RNG_SEED,
          global.schemaInfo.getDataInterval(),
          global.rowsPerSegment
      );

      IncrementalIndex incIndex = global.makeIncIndex(global.schemaInfo.getAggsArray());

      for (int j = 0; j < global.rowsPerSegment; j++) {
        InputRow row = gen.nextRow();
        // if (j % 10000 == 0) {
        //   log.info(j + " rows generated.");
        // }
        incIndex.add(row);
      }

      qIndexFile = INDEX_MERGER_V9.persist(
          incIndex,
          FileUtils.createTempDir(),
          new IndexSpec(),
          null
      );
      incIndex.close();

      qIndex = INDEX_IO.loadIndex(qIndexFile);
    }

    @TearDown
    public void tearDown()
    {
      qIndexFile.delete();
    }
  }

  private IncrementalIndex makeIncIndex(AggregatorFactory[] metrics)
  {
    return new IncrementalIndex.Builder()
        .setSimpleTestingIndexSchema(metrics)
        .setReportParseExceptions(false)
        .setMaxRowCount(rowsPerSegment)
        .build(indexType);
  }

  private static <T> List<T> runQuery(QueryRunnerFactory factory, QueryRunner runner, Query<T> query, String vectorize)
  {
    QueryToolChest toolChest = factory.getToolchest();
    QueryRunner<T> theRunner = new FinalizeResultsQueryRunner<>(
        toolChest.mergeResults(toolChest.preMergeQueryDecoration(runner)),
        toolChest
    );

    final QueryPlus<T> queryToRun = QueryPlus.wrap(
        query.withOverriddenContext(ImmutableMap.of("vectorize", vectorize))
    );
    Sequence<T> queryResult = theRunner.run(queryToRun, ResponseContext.createEmpty());
    return queryResult.toList();
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void ingest(Blackhole blackhole, FilteredIncrementalIndexState state) throws Exception
  {
    for (InputRow row : state.inputRows) {
      int rv = state.incIndex.add(row).getRowCount();
      blackhole.consume(rv);
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void querySingleIncrementalIndex(Blackhole blackhole, IncrementalIndexState state)
  {
    QueryRunner<Result<TimeseriesResultValue>> runner = QueryBenchmarkUtil.makeQueryRunner(
        factory,
        SegmentId.dummy("incIndex"),
        new IncrementalIndexSegment(state.incIndex, SegmentId.dummy("incIndex"))
    );

    List<Result<TimeseriesResultValue>> results = FilteredAggregatorBenchmark.runQuery(
        factory,
        runner,
        query,
        vectorize
    );
    for (Result<TimeseriesResultValue> result : results) {
      blackhole.consume(result);
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void querySingleQueryableIndex(Blackhole blackhole, QueryableIndexState state)
  {
    final QueryRunner<Result<TimeseriesResultValue>> runner = QueryBenchmarkUtil.makeQueryRunner(
        factory,
        SegmentId.dummy("qIndex"),
        new QueryableIndexSegment(state.qIndex, SegmentId.dummy("qIndex"))
    );

    List<Result<TimeseriesResultValue>> results = FilteredAggregatorBenchmark.runQuery(
        factory,
        runner,
        query,
        vectorize
    );
    for (Result<TimeseriesResultValue> result : results) {
      blackhole.consume(result);
    }
  }

  public static void main(String[] args) throws RunnerException
  {
    Options opt = new OptionsBuilder()
        .include(FilteredAggregatorBenchmark.class.getSimpleName() + ".querySingleIncrementalIndex$")
        .warmupIterations(3)
        .measurementIterations(10)
        // .measurementTime(TimeValue.NONE)
        .forks(0)
        .threads(1)
        .param("indexType", "oak")
        .param("rollup", "false")
        .param("vectorize", "false")
        // .param("rowsPerSegment", "2000000")
        .build();

    new Runner(opt).run();
  }
}
