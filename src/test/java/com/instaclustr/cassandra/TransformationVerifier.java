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
package com.instaclustr.cassandra;

import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

class TransformationVerifier
{
    public static void verify(int expectedCount,
                              List<? extends AbstractFile<?>> outputFiles,
                              TransformerOptions options) throws IOException
    {
        int actualCount = 0;
        for (AbstractFile<?> file : outputFiles)
        {
            if (options.outputFormat == TransformerOptions.OutputFormat.PARQUET)
                actualCount += verifyParquet(file, options);
            else
                actualCount += verifyAvro(file, options);
        }

        assertEquals(expectedCount, actualCount);
    }

    private static int verifyParquet(AbstractFile<?> file, TransformerOptions options) throws IOException
    {
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(file)
                .usePageChecksumVerification()
                .useBloomFilter(options.bloomFilterEnabled)
                .build())
        {
            int count = 0;
            GenericRecord record;
            Integer previous = null;
            while ((record = reader.read()) != null)
            {
                Integer next = (Integer) record.get("id");
                if (previous != null && options.sorted)
                {
                    // proof that they are sorted inside file
                    assertTrue(previous < next);
                }
                previous = next;
                ++count;
            }

            if (options.maxRowsPerFile != -1)
                Assert.assertTrue(options.maxRowsPerFile >= count);

            return count;
        }
    }

    private static int verifyAvro(AbstractFile<?> file, TransformerOptions options) throws IOException
    {
        try (org.apache.avro.file.FileReader<GenericRecord> fileReader = DataFileReader.openReader(new File(file.getPath()), new GenericDatumReader<>()))
        {
            int count = 0;
            GenericRecord record;
            Integer previous = null;
            while (fileReader.hasNext())
            {
                record = fileReader.next();
                Integer next = (Integer) record.get("id");
                if (previous != null && options.sorted)
                {
                    // proof that they are sorted inside file
                    assertTrue(previous < next);
                }
                previous = next;
                ++count;
            }

            if (options.maxRowsPerFile != -1)
                Assert.assertTrue(options.maxRowsPerFile >= count);

            return count;
        }
    }
}
