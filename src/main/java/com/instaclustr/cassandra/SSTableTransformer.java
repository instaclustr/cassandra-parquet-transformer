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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Mixin;

import java.util.Collection;
import java.util.List;

import static com.instaclustr.cassandra.DataLayerWrapperBuilders.getLocalDataLayers;
import static com.instaclustr.cassandra.DataLayerWrapperBuilders.getRemoteDataLayers;
import static com.instaclustr.cassandra.TransformerOptions.DataLayerLocation.REMOTE;
import static java.util.stream.Collectors.toList;

@Command(name = "transform",
        description = "Transform SSTables to Parquet or Avro files.",
        versionProvider = Transformer.class,
        subcommands = HelpCommand.class)
public class SSTableTransformer implements Runnable
{
    private static final Logger logger = LoggerFactory.getLogger(SSTableTransformer.class);

    @Mixin
    private TransformerOptions options;

    // for picocli
    public SSTableTransformer()
    {
    }

    public SSTableTransformer(TransformerOptions options)
    {
        this.options = options;
        options.validate();
    }

    @Override
    public void run()
    {
        runTransformation();
    }

    /**
     * Runs transformation. Used for programmatic invocation, e.g. via Spark job.
     *
     * @return list of files which were created as part of transformation
     */
    public List<? extends AbstractFile<?>> runTransformation()
    {
        try (TransformationExecutor executor = new TransformationExecutor(options.parallelism))
        {
            Collection<DataLayerTransformer> transformers;
            if (options.resolveDataLayerLocation() == REMOTE)
                transformers = getRemoteDataLayers(options)
                        .stream()
                        .map(wrapper -> new DataLayerTransformer(options, wrapper))
                        .collect(toList());
            else
                transformers = getLocalDataLayers(options)
                        .stream()
                        .map(wrapper -> new DataLayerTransformer(options, wrapper))
                        .collect(toList());


            List<? extends AbstractFile<?>> outputFiles = executor.run(transformers);

            for (AbstractFile<?> outputFile : outputFiles)
                logger.info(outputFile.getPath());

            return outputFiles;
        }
    }
}
