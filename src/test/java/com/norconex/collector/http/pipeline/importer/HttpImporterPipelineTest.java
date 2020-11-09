/* Copyright 2015-2020 Norconex Inc.
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
package com.norconex.collector.http.pipeline.importer;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.norconex.collector.core.doc.CrawlDoc;
import com.norconex.collector.http.HttpCollector;
import com.norconex.collector.http.crawler.HttpCrawler;
import com.norconex.collector.http.crawler.HttpCrawlerConfig;
import com.norconex.collector.http.crawler.HttpCrawlerConfig.ReferencedLinkType;
import com.norconex.collector.http.doc.HttpDocInfo;
import com.norconex.collector.http.doc.HttpDocMetadata;
import com.norconex.collector.http.fetch.HttpMethod;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.importer.doc.DocMetadata;

/**
 * @author Pascal Essiembre
 * @since 2.2.0
 */
class HttpImporterPipelineTest {

    @Test
    void testCanonicalStageSameReferenceContent() {
        String reference = "http://www.example.com/file.pdf";
        String contentValid = "<html><head><title>Test</title>\n"
                + "<link rel=\"canonical\"\n href=\"\n" + reference +  "\" />\n"
                + "</head><body>Nothing of interest in body</body></html>";
        CrawlDoc doc = new CrawlDoc(new HttpDocInfo(reference, 0), null,
                new CachedStreamFactory(1000, 1000).newInputStream(
                        new ByteArrayInputStream(contentValid.getBytes())),
                                false);
        HttpImporterPipelineContext ctx = new HttpImporterPipelineContext(
                new HttpCrawler(new HttpCrawlerConfig(), new HttpCollector()),
                doc);
        Assertions.assertTrue(
                new CanonicalStage(HttpMethod.GET).execute(ctx));
    }

    @Test
    void testCanonicalStageSameReferenceHeader() {
        String reference = "http://www.example.com/file.pdf";
        CrawlDoc doc = new CrawlDoc(new HttpDocInfo(reference, 0), null,
                new CachedStreamFactory(1, 1).newInputStream(), false);
        doc.getMetadata().set("Link", "<" + reference + "> rel=\"canonical\"");
        HttpImporterPipelineContext ctx = new HttpImporterPipelineContext(
                new HttpCrawler(new HttpCrawlerConfig(), new HttpCollector()),
                doc);
        Assertions.assertTrue(
                new CanonicalStage(HttpMethod.HEAD).execute(ctx));
    }


    @Test
    void testKeepMaxDepthLinks() throws IllegalAccessException {
        String reference = "http://www.example.com/file.html";
        String content = "<html><head><title>Test</title>\n"
                + "</head><body><a href=\"link.html\">A link</a></body></html>";


        HttpDocInfo docInfo = new HttpDocInfo(reference, 2);
        docInfo.setContentType(ContentType.HTML);

        CrawlDoc doc = new CrawlDoc(docInfo, null,
                new CachedStreamFactory(1000, 1000).newInputStream(
                        new ByteArrayInputStream(content.getBytes())), false);
        doc.getMetadata().set(DocMetadata.CONTENT_TYPE, "text/html");

        HttpImporterPipelineContext ctx = new HttpImporterPipelineContext(
                new HttpCrawler(
                        new HttpCrawlerConfig(), new HttpCollector()), doc);
        ctx.getConfig().setMaxDepth(2);

        LinkExtractorStage stage = new LinkExtractorStage();

        // By default do not extract urls on max depth
        stage.execute(ctx);
        Assertions.assertEquals(0, doc.getMetadata().getStrings(
                HttpDocMetadata.REFERENCED_URLS).size());

        // Here 1 URL shouled be extracted even if max depth is reached.
        ctx.getConfig().setKeepReferencedLinks(
                ReferencedLinkType.INSCOPE, ReferencedLinkType.MAXDEPTH);
        stage.execute(ctx);
        Assertions.assertEquals(1, doc.getMetadata().getStrings(
                HttpDocMetadata.REFERENCED_URLS).size());
    }

}
