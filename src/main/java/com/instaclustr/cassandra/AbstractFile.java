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
import org.apache.parquet.io.DelegatingSeekableInputStream;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.io.SeekableInputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class AbstractFile<T extends AbstractFile<T>> implements OutputFile, InputFile
{
    private RandomAccessFile file;
    private final OutputFormat outputFormat;
    private final Path internalPath;
    private final int number;
    private boolean finished = false;
    private int rows;

    public AbstractFile(OutputFormat outputFormat,
                        Path path,
                        int number)
    {
        this.outputFormat = outputFormat;
        this.internalPath = path;
        this.number = number;
    }

    public OutputFormat getOutputFormat()
    {
        return outputFormat;
    }

    public Path getInternalPath()
    {
        return internalPath;
    }

    public int getNumber()
    {
        return number;
    }

    public int nextNumber()
    {
        return number + 1;
    }

    /**
     * Returns logically next output file a transformation process should write data to when this one is considered full.
     *
     * @return next output file.
     */
    public abstract T next();

    @Override
    public PositionOutputStream create(long blockSizeHint) throws IOException
    {
        return new PositionOutputStreamWrapper(Files.newOutputStream(Paths.get(getPath())));
    }

    @Override
    public PositionOutputStream createOrOverwrite(long blockSizeHint) throws IOException
    {
        return create(blockSizeHint);
    }

    @Override
    public boolean supportsBlockSize()
    {
        return false;
    }

    @Override
    public long defaultBlockSize()
    {
        return 0;
    }

    /**
     * After this method is called, no rows should be written to it anymore.
     */
    public void finish()
    {
        finished = true;
    }

    /**
     * A file is considered to be eligible to be written to when it is not finished.
     * <p>
     * See {@link AbstractFile#finished}.
     *
     * @return true if we can write to this file, false otherwise.
     */
    public boolean canWrite()
    {
        return !finished;
    }

    public void setRows(int rows)
    {
        this.rows = rows;
    }

    public int getRows()
    {
        return rows;
    }

    @Override
    public long getLength() throws IOException
    {
        return Files.size(Paths.get(getPath()));
    }

    @Override
    public SeekableInputStream newStream() throws IOException
    {
        if (file == null)
        {
            file = new RandomAccessFile(getPath(), "r");
        }

        return new DelegatingSeekableInputStream(Channels.newInputStream(file.getChannel()))
        {
            @Override
            public long getPos() throws IOException
            {
                return file.getFilePointer();
            }

            @Override
            public void seek(long newPos) throws IOException
            {
                file.seek(newPos);
            }
        };
    }

    @Override
    public String toString()
    {
        return getPath();
    }

    private static class PositionOutputStreamWrapper extends PositionOutputStream
    {
        private final OutputStream out;
        private long position = 0;

        PositionOutputStreamWrapper(OutputStream out)
        {
            this.out = out;
        }

        @Override
        public long getPos()
        {
            return position;
        }

        @Override
        public void write(int b) throws IOException
        {
            out.write(b);
            position++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            out.write(b, off, len);
            position += len;
        }

        @Override
        public void flush() throws IOException
        {
            out.flush();
        }

        @Override
        public void close() throws IOException
        {
            out.close();
        }
    }
}
