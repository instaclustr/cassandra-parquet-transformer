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

import com.instaclustr.cassandra.TransformerOptions.OutputFormat;
import org.apache.avro.Schema;
import org.apache.cassandra.spark.sparksql.SparkRowIterator;
import org.apache.spark.sql.avro.SchemaConverters;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.RowOrdering;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;
import scala.collection.Seq;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

/**
 * Transforms stream of rows from Cassandra by {@link SparkRowIterator}.
 * <p>
 * As rows are being transformed and written to a respective file, if that file is considered to be full,
 * it will take care of switching the writer to the next output file.
 */
public class DataLayerReader
{
    private final DataLayerWrapper dataLayerWrapper;
    private final TransformerOptions options;

    public DataLayerReader(DataLayerWrapper dataLayerWrapper, TransformerOptions options)
    {
        this.dataLayerWrapper = dataLayerWrapper;
        this.options = options;
    }

    public Collection<? extends AbstractFile<?>> read()
    {
        try (RowsWriter rowsWriter = resolveRowsWriter(options))
        {
            return rowsWriter.write();
        }
    }

    private RowsWriter resolveRowsWriter(TransformerOptions options)
    {
        if (options.sorted)
            return new SortedRowsWriter(dataLayerWrapper, options);
        else
            return new UnsortedRowsWriter(dataLayerWrapper, options);
    }

    private static abstract class RowsWriter implements AutoCloseable
    {
        private static final Logger logger = LoggerFactory.getLogger(RowsWriter.class);

        protected final List<AbstractFile<?>> outputFiles = new ArrayList<>();
        protected final DataLayerWrapper dataLayerWrapper;
        protected final TransformerOptions options;
        private final Schema avroSchema;
        protected final long maxRowsPerFile;
        protected final StructType structType;

        protected int count = 0;
        protected long start = currentTimeMillis();
        protected GenericRowWriter rowWriter;

        protected RowsWriter(DataLayerWrapper dataLayerWrapper, TransformerOptions options)
        {
            this.dataLayerWrapper = dataLayerWrapper;
            this.options = options;
            structType = this.dataLayerWrapper.getDataLayer().structType();
            maxRowsPerFile = dataLayerWrapper.getMaxRowsPerParquetFile();
            outputFiles.add(dataLayerWrapper.currentDestination());
            avroSchema = SchemaConverters.toAvroType(structType,
                                                     false,
                                                     "root",
                                                     SSTableTransformer.class.getCanonicalName());

            switchWriter(dataLayerWrapper.currentDestination(), options.outputFormat);
        }

        public List<AbstractFile<?>> write()
        {
            try (SparkRowIterator iterator = new SparkRowIterator(dataLayerWrapper.getPartition(),
                                                                  dataLayerWrapper.getDataLayer(),
                                                                  dataLayerWrapper.getDataLayer().structType(),
                                                                  emptyList()))
            {
                internalWrite(iterator);
            }
            catch (Throwable t)
            {
                throw new RuntimeException(t);
            }
            finally
            {
                try
                {
                    if (rowWriter != null)
                        rowWriter.close();
                }
                catch (Exception ex)
                {
                    logger.warn("Unable to close Parquet row writer.", ex);
                }
            }

            return outputFiles;
        }

        protected abstract void internalWrite(SparkRowIterator iterator) throws IOException;

        protected long printDuration(AbstractFile<?> outputFile, int count, long start, long end)
        {
            logger.info("Transformed {} rows to {} in {} seconds.",
                        count,
                        outputFile.getPath(),
                        (end - start) / 1000);

            // new start
            return currentTimeMillis();
        }

        protected void switchWriter(AbstractFile<?> destination, OutputFormat outputFormat)
        {
            if (!destination.canWrite())
                throw new IllegalStateException("Can not write to " + destination.getPath());

            try
            {
                close();

                switch (outputFormat)
                {
                    case AVRO:
                        rowWriter = new AvroRowWriter(dataLayerWrapper.getDataLayer(),
                                                      avroSchema,
                                                      destination,
                                                      options);
                        break;
                    case PARQUET:
                        rowWriter = new ParquetRowWriter(dataLayerWrapper.getDataLayer(),
                                                         avroSchema,
                                                         destination,
                                                         options);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported output format " + outputFormat.name());
                }
            }
            catch (Throwable t)
            {
                throw new TransformerException("Error while closing current Spark row consumer", t);
            }
        }

        @Override
        public void close()
        {
            if (rowWriter != null)
            {
                try
                {
                    rowWriter.close();
                    rowWriter = null;
                }
                catch (Throwable t)
                {
                    throw new TransformerException("Error while closing ParquetRowWriter.", t);
                }
            }
        }
    }

    private static class UnsortedRowsWriter extends RowsWriter
    {
        public UnsortedRowsWriter(DataLayerWrapper dataLayerWrapper, TransformerOptions options)
        {
            super(dataLayerWrapper, options);
        }

        @Override
        protected void internalWrite(SparkRowIterator iterator) throws IOException
        {
            while (iterator.next())
            {
                if (count == maxRowsPerFile)
                {
                    start = printDuration(dataLayerWrapper.currentDestination(),
                                          count,
                                          start,
                                          currentTimeMillis());

                    dataLayerWrapper.currentDestination().setRows(count);
                    AbstractFile<?> nextDestination = dataLayerWrapper.getNextDestination();
                    switchWriter(nextDestination, options.outputFormat);
                    outputFiles.add(nextDestination);
                    count = 0;
                }

                rowWriter.accept(iterator.get());
                count++;
            }

            if (count != 0)
                printDuration(dataLayerWrapper.currentDestination(), count, start, currentTimeMillis());
        }
    }

    private static class SortedRowsWriter extends RowsWriter
    {
        private final Comparator<InternalRow> rowComparator;
        private final InternalRow[] rowsBuffer;

        public SortedRowsWriter(DataLayerWrapper dataLayerWrapper, TransformerOptions options)
        {
            super(dataLayerWrapper, options);
            List<DataType> javaSchema = stream(structType.fields()).map(StructField::dataType).collect(toList());
            Seq<DataType> scalaSchema = JavaConverters.asScalaBuffer(javaSchema).toSeq();
            rowComparator = RowOrdering.createNaturalAscendingOrdering(scalaSchema);

            int arraySize = (int) maxRowsPerFile;
            if (arraySize == -1)
                arraySize = Integer.parseInt(System.getProperty("transformer.buffer.size", "10000000"));

             rowsBuffer = new InternalRow[arraySize];
        }

        @Override
        protected void internalWrite(SparkRowIterator iterator) throws IOException
        {
            boolean shouldSwitch = false;

            while (iterator.next())
            {
                if (shouldSwitch)
                {
                    outputFiles.add(dataLayerWrapper.getNextDestination());
                    switchWriter(dataLayerWrapper.currentDestination(), options.outputFormat);
                    shouldSwitch = false;
                }

                rowsBuffer[count++] = iterator.get();

                if (count == maxRowsPerFile)
                {
                    sortAndWrite(rowsBuffer);
                    start = printDuration(dataLayerWrapper.currentDestination(),
                                          count,
                                          start,
                                          currentTimeMillis());
                    dataLayerWrapper.currentDestination().setRows(count);
                    count = 0;
                    shouldSwitch = true;
                }
            }

            // if we should switch and we have at least something
            if (shouldSwitch && rowsBuffer[0] != null)
            {
                dataLayerWrapper.currentDestination().setRows(count);
                outputFiles.add(dataLayerWrapper.getNextDestination());
                switchWriter(dataLayerWrapper.currentDestination(), options.outputFormat);
            }

            sortAndWrite(rowsBuffer);

            if (count != 0)
                printDuration(dataLayerWrapper.currentDestination(), count, start, currentTimeMillis());
        }

        private void sortAndWrite(InternalRow[] rows)
        {
            Arrays.sort(rows, Comparator.nullsLast(rowComparator));
            for (InternalRow sorted : rows)
            {
                if (sorted != null)
                    rowWriter.accept(sorted);
                else
                    break;
            }

            Arrays.fill(rows, null);
        }
    }
}
