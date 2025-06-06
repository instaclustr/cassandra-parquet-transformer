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

import java.util.Collection;

/**
 * This class is responsible for taking an instance of {@link DataLayerWrapper}, being it
 * local or remote, and transforming data it is responsible for to Parquet files by iterating over
 * all rows by {@link DataLayerReader}.
 */
public class DataLayerTransformer
{
    private final TransformerOptions options;
    private final DataLayerWrapper dataLayerWrapper;

    public DataLayerTransformer(TransformerOptions options, DataLayerWrapper dataLayerWrapper)
    {
        this.options = options;
        this.dataLayerWrapper = dataLayerWrapper;
    }

    /**
     * Transforms data.
     *
     * @return list of Parquet files which were created by transformation.
     */
    public Collection<? extends AbstractFile<?>> transform()
    {
        try
        {
            return new DataLayerReader(dataLayerWrapper, options).read();
        }
        catch (Throwable t)
        {
            throw new TransformerException("Unable to transform to " + options.outputFormat + " file(s)", t);
        }
    }
}
