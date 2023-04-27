/* Copyright 2020-2022 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.crawler.core.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.commons.io.FileUtils;

import com.norconex.commons.lang.xml.XML;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Resolve all includes and variables substitution and print the
 * resulting configuration to facilitate sharing.
 */
@Command(
    name = "configrender",
    description = "Render effective configuration"
)
@EqualsAndHashCode
@ToString
public class ConfigRenderCommand extends AbstractSubCommand {

    @Option(names = { "-o", "-output" },
            description = "Render to a file",
            required = false)
    private Path output;

    @Option(names = { "-i", "-indent" },
            description = "Number of spaces used for indentation (default: 2).",
            required = false)
    private int indent = 2;

    @Override
    public void runCommand() {
        var xml = new XML("collector");
        getCrawlSession().getCrawlSessionConfig().saveToXML(xml);

        var renderedConfig = xml.toString(indent);

        if (output != null) {
            try {
                FileUtils.write(output.toFile(), renderedConfig, UTF_8);
            } catch (IOException e) {
                e.printStackTrace(System.err);
            }
        } else {
            printOut(renderedConfig);
        }
    }
}