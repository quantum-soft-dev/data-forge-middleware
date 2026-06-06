package com.bitbi.dfm.delta.application;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.1 — smoke proof that the chosen Parquet writer (parquet-avro / parquet-mr) round-trips a typed
 * file Hadoop-free: written via {@link LocalOutputFile} + {@link PlainParquetConfiguration} and read
 * back with the column types intact. This pins the dependency before the egress work (T4.2+).
 */
class ParquetWriterSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void writesAndReadsTypedParquetWithoutHadoop() throws Exception {
        Schema schema = SchemaBuilder.record("customers").namespace("com.bitbi.dfm.delta")
                .fields()
                .name("id").type().longType().noDefault()
                .name("name").type().stringType().noDefault()
                .name("amount").type().doubleType().noDefault()
                .name("active").type().booleanType().noDefault()
                .endRecord();

        Path file = tempDir.resolve("customers.parquet");

        GenericRecord r1 = new GenericData.Record(schema);
        r1.put("id", 1L);
        r1.put("name", "Ann");
        r1.put("amount", 12.50d);
        r1.put("active", true);
        GenericRecord r2 = new GenericData.Record(schema);
        r2.put("id", 2L);
        r2.put("name", "Bob");
        r2.put("amount", 7.25d);
        r2.put("active", false);

        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(new LocalOutputFile(file))
                .withSchema(schema)
                .withConf(new PlainParquetConfiguration())
                .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)
                .build()) {
            writer.write(r1);
            writer.write(r2);
        }

        assertTrue(Files.size(file) > 0, "parquet file written");

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withConf(new PlainParquetConfiguration())
                .build()) {
            GenericRecord first = reader.read();
            assertEquals(1L, first.get("id"), "long type preserved");
            assertEquals("Ann", first.get("name").toString(), "string type preserved");
            assertEquals(12.50d, first.get("amount"), "double type preserved");
            assertEquals(true, first.get("active"), "boolean type preserved");

            GenericRecord second = reader.read();
            assertEquals(2L, second.get("id"));
            assertEquals("Bob", second.get("name").toString());

            assertNull(reader.read(), "exactly two records");
        }
    }
}
