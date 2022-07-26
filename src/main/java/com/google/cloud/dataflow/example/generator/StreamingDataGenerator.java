/*
 * Copyright (C) 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.example.generator;

import com.google.cloud.dataflow.example.generator.envelope.EnvelopeCompression;
import com.google.common.base.Splitter;
import com.google.common.math.Quantiles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.GenerateSequence;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generator pipeline which outputs PubSub messages.
 */
public class StreamingDataGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(StreamingDataGenerator.class);

  /**
   * Options supported by {@link PubsubIO}.
   *
   * <p>
   * Inherits standard configuration options.
   */
  public interface SparrowLoadGeneratorOptions extends DataflowPipelineOptions {

    @Description("Output Pubsub topic")
    @Validation.Required
    String getOutputTopic();

    void setOutputTopic(String value);

    @Description("How many raw events will be generated every second")
    @Default.Integer(299000)
    Integer getGeneratorRatePerSec();

    void setGeneratorRatePerSec(Integer value);

    @Description("How many events will be batched together")
    @Default.Integer(5000)
    Integer getMaxRecordsPerBatch();

    void setMaxRecordsPerBatch(Integer value);

    @Description("FQCN of the type that should be generated")
    @Default.String("com.google.cloud.dataflow.example.LogEvent")
    String getClassName();

    void setClassName(String value);

    @Description("Min char count for a generated String")
    @Default.Integer(1)
    Integer getMinStringLength();

    void setMinStringLength(Integer value);

    @Description("Max char count for a generated String")
    @Default.Integer(20)
    Integer getMaxStringLength();

    void setMaxStringLength(Integer value);

    @Description("Max elements on a collection type")
    @Default.Integer(10)
    Integer getMaxSizeCollection();

    void setMaxSizeCollection(Integer value);

    @Description("Enables ZLIB compression and batched envelope payloads")
    @Default.Boolean(true)
    Boolean isCompressionEnabled();

    void setCompressionEnabled(Boolean value);

    @Description("Object generation produces complete populated instances")
    @Default.Boolean(false)
    Boolean isCompleteObjects();

    void setCompleteObjects(Boolean value);

    @Description("Object generation produces complete populated instances")
    @Default.Integer(1)
    Integer getCompressionLevel();

    void setCompressionLevel(Integer value);

    @Description("Format of the generated messages")
    @Default.Enum("THRIFT")
    DataGenerator.Format getFormat();

    void setFormat(DataGenerator.Format value);

    @Description("File path to copy already generated data from")
    @Default.String("")
    String getFilePath();

    void setFilePath(String value);
  }

  static final Map<String, String> EMPTY_ATTRS = new HashMap<>();
  static final String COMPRESSION_TYPE_HEADER_KEY = "compression";
  static final String AVRO_SCHEMA_ATTRIBUTE = "AVRO_SCHEMA_ATTRIBUTE";

  /**
   * Supported compression types.
   */
  public enum CompressionType {
    NO_COMPRESSION, // default
    ZLIB,
  }

  /**
   * Sets up and starts generator pipeline.
   *
   * @param args
   * @throws java.lang.ClassNotFoundException
   */
  public static void main(String[] args) throws Exception {
    SparrowLoadGeneratorOptions options
            = PipelineOptionsFactory.fromArgs(args)
                    .withValidation()
                    .as(SparrowLoadGeneratorOptions.class);
    // setting as a streaming pipeline
    options.setStreaming(true);
    
    // Run generator pipeline
    Pipeline generator = Pipeline.create(options);

    // capture class of the type that will be generated
    Class clazz = Class.forName(options.getClassName());

    // create a data generator for this class and based on the configured options
    DataGenerator gen
            = DataGenerator.createDataGenerator(
                    options.getFormat(),
                    clazz,
                    options.getMinStringLength(),
                    options.getMaxStringLength(),
                    options.getMaxSizeCollection(),
                    options.getFilePath());

    int seqGeneratorRate = options.isCompressionEnabled()
            ? options.getGeneratorRatePerSec() / options.getMaxRecordsPerBatch()
            : options.getGeneratorRatePerSec();

    LOG.info("Generating {} raw elements per second.", options.getGeneratorRatePerSec());
    if (options.isCompressionEnabled()) {
      LOG.info("Batch {} elements together", options.getMaxRecordsPerBatch());
    }
    LOG.info("Sequence gen rate at {}", seqGeneratorRate);

    generator
            .apply(
                    GenerateSequence
                            .from(0L)
                            .withRate(
                                    seqGeneratorRate,
                                    Duration.standardSeconds(1)))
            .apply(String.format("Create%sPayloadForPubsub", options.getFormat().name()),
                    ParDo.of(
                            new CreatePubSubMessage(
                                    gen,
                                    options.isCompressionEnabled(),
                                    options.isCompleteObjects(),
                                    options.getCompressionLevel(),
                                    options.getMaxRecordsPerBatch())))
            .apply("WriteToPubsub", PubsubIO.writeMessages().to(options.getOutputTopic()));
    generator.run();
  }

  static class CreatePubSubMessage extends DoFn<Long, PubsubMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(CreatePubSubMessage.class);

    private static final Distribution TIME_TO_GENERATE_BATCH
            = Metrics.distribution(CreatePubSubMessage.class, "batch_generation_ms");
    private static final Distribution BATCH_SIZE
            = Metrics.distribution(CreatePubSubMessage.class, "batch_compressed_size_bytes");
    private static final Distribution BATCH_RAW_SIZE
            = Metrics.distribution(CreatePubSubMessage.class, "batch_raw_size_bytes");
    private static final Distribution RAW_SIZE
            = Metrics.distribution(CreatePubSubMessage.class, "object_raw_size_bytes");

    private final boolean compressionEnabled;
    private final boolean generateCompleteObjects;
    private final DataGenerator gen;
    private final int compressionLevel;
    private final int recordsPerImpulse;
    private final List<Long> sizes = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();

    public CreatePubSubMessage(
            DataGenerator dataGenerator,
            boolean compressionEnabled,
            boolean generateCompleteObjects,
            int compressionLevel,
            int recordsPerImpulse) {
      this.gen = dataGenerator;
      this.generateCompleteObjects = generateCompleteObjects;
      this.compressionLevel = compressionLevel;
      this.compressionEnabled = compressionEnabled;
      this.recordsPerImpulse = recordsPerImpulse;
    }

    @Setup
    public void setup() throws Exception {
      gen.init();
    }

    @StartBundle
    public void startBundle() {
      sizes.clear();
      times.clear();
    }

    @ProcessElement
    public void processElement(ProcessContext context) throws Exception {
      if (compressionEnabled) {
        long startTimeBatch = System.currentTimeMillis();
        long rawDataSize = 0;
        List<com.google.cloud.dataflow.example.Element> records = new ArrayList<>();
        for (int i = 0; i < recordsPerImpulse; i++) {
          byte[] message = makeMessage().getKey();
          rawDataSize += message.length;
          RAW_SIZE.update(message.length);
          records.add(EnvelopeCompression.constructThriftRecord(message, EMPTY_ATTRS));
        }
        byte[] compressedData
                = EnvelopeCompression.compressBatchRecords(
                        EnvelopeCompression.constructThriftBatchRecord(records, EMPTY_ATTRS),
                        compressionLevel);
        TIME_TO_GENERATE_BATCH.update(System.currentTimeMillis() - startTimeBatch);
        BATCH_RAW_SIZE.update(rawDataSize);
        BATCH_SIZE.update(compressedData.length);
        context.output(
                new PubsubMessage(
                        compressedData,
                        Map.of(COMPRESSION_TYPE_HEADER_KEY, CompressionType.ZLIB.name())));

      } else {
        KV<byte[], String> messageAndSchema = makeMessage();
        // compress the schema
        String compressedSchema = EnvelopeCompression.compressString(messageAndSchema.getValue());
        // partition the schema in multiple strings of ~800 bytes (under the limit of 1k per map entry) 
        // and build an attribute map with it
        Map<String, String> attributeMap = Splitter.fixedLength(400)
                .splitToList(compressedSchema)
                .stream()
                .collect(
                        HashMap::new,
                        (Map<Integer, String> m, String s) -> m.put(m.size() + 1, s),
                        (m1, m2) -> {
                          int offset = m1.size();
                          m2.forEach((i, s) -> m1.put(i + offset, s));
                        })
                .entrySet()
                .stream()
                .map(e -> KV.of(AVRO_SCHEMA_ATTRIBUTE + e.getKey(), e.getValue()))
                .collect(Collectors.toMap(KV::getKey, KV::getValue));
        context.output(new PubsubMessage(messageAndSchema.getKey(), attributeMap));
      }
    }
    
    @FinishBundle
    public void finalizeBundle() {
      LOG.info("Gen size percentiles (bytes): {}", Quantiles.percentiles().indexes(50, 90, 95).compute(sizes).toString());
      LOG.info("Gen time percentiles (ns): {}", Quantiles.percentiles().indexes(50, 90, 95).compute(times).toString());
    }

    KV<byte[], String> makeMessage() {
      try {
        long objectStartTime = System.nanoTime();
        KV<byte[], String> serializedMessage
                = gen.createInstanceAsBytesAndSchemaAsStringIfPresent(generateCompleteObjects);
        times.add(System.nanoTime() - objectStartTime);
        sizes.add((long) serializedMessage.getKey().length);

        return serializedMessage;
      } catch (Exception e) {
        throw new RuntimeException("Error while serializing the object.", e);
      }
    }

  }
}
