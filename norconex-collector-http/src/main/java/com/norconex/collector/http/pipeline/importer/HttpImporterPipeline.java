/* Copyright 2010-2015 Norconex Inc.
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

import java.io.IOException;
import java.io.Reader;
import java.util.Set;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.norconex.collector.core.CollectorException;
import com.norconex.collector.core.data.CrawlState;
import com.norconex.collector.core.data.store.ICrawlDataStore;
import com.norconex.collector.core.pipeline.importer.DocumentFiltersStage;
import com.norconex.collector.core.pipeline.importer.ImportModuleStage;
import com.norconex.collector.core.pipeline.importer.ImporterPipelineContext;
import com.norconex.collector.core.pipeline.importer.ImporterPipelineUtil;
import com.norconex.collector.core.pipeline.importer.SaveDocumentStage;
import com.norconex.collector.http.crawler.HttpCrawlerEvent;
import com.norconex.collector.http.crawler.TargetURLRedirectStrategy;
import com.norconex.collector.http.data.HttpCrawlData;
import com.norconex.collector.http.data.HttpCrawlState;
import com.norconex.collector.http.delay.IDelayResolver;
import com.norconex.collector.http.doc.HttpMetadata;
import com.norconex.collector.http.doc.IHttpDocumentProcessor;
import com.norconex.collector.http.fetch.HttpFetchResponse;
import com.norconex.collector.http.fetch.IHttpMetadataFetcher;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.pipeline.Pipeline;

/**
 * @author Pascal Essiembre
 *
 */
public class HttpImporterPipeline 
        extends Pipeline<ImporterPipelineContext> {

    
    private static final Logger LOG = 
            LogManager.getLogger(HttpImporterPipeline.class);
    
    //TODO create a DocumentPipelinePrototype to generate prototypes
    //sharing all thread safe/common information, 
    //just changing what is url/doc specific.
    
    public HttpImporterPipeline(boolean isKeepDownloads) {
        addStage(new DelayResolverStage());

        // When HTTP headers are fetched (HTTP "HEAD") before document:
        addStage(new HttpMetadataFetcherStage());
        addStage(new HttpMetadataFiltersHEADStage());
        addStage(new HttpMetadataCanonicalHEADStage());
        addStage(new HttpMetadataChecksumStage(true));
        
        // HTTP "GET" and onward:
        addStage(new DocumentFetcherStage());
        if (isKeepDownloads) {
            addStage(new SaveDocumentStage());
        }
        addStage(new HttpMetadataCanonicalGETStage());
        addStage(new DocumentCanonicalStage());
        addStage(new RobotsMetaCreateStage());
        addStage(new LinkExtractorStage());
        addStage(new RobotsMetaNoIndexStage());
        addStage(new HttpMetadataFiltersGETStage());
        addStage(new HttpMetadataChecksumStage(false));
        addStage(new DocumentFiltersStage());
        addStage(new DocumentPreProcessingStage());        
        addStage(new ImportModuleStage());        
    }

    //--- Wait for delay to expire ---------------------------------------------
    private static class DelayResolverStage extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            IDelayResolver delayResolver = ctx.getConfig().getDelayResolver();
            if (delayResolver != null) {
                if (!ctx.getConfig().isIgnoreRobotsTxt()) {
                    delayResolver.delay(
                            ctx.getConfig().getRobotsTxtProvider().getRobotsTxt(
                                    ctx.getHttpClient(), 
                                    ctx.getCrawlData().getReference(), 
                                    ctx.getConfig().getUserAgent()), 
                            ctx.getCrawlData().getReference());
                } else {
                    delayResolver.delay(
                            null, ctx.getCrawlData().getReference());
                }
            }
            return true;
        }
    }

    //--- HTTP Headers Fetcher -------------------------------------------------
    private static class HttpMetadataFetcherStage 
            extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            if (!ctx.isHttpHeadFetchEnabled()) {
                return true;
            }

            HttpMetadata metadata = ctx.getMetadata();
            IHttpMetadataFetcher headersFetcher = ctx.getHttpHeadersFetcher();
            HttpCrawlData crawlData = ctx.getCrawlData();
            Properties headers = headersFetcher.fetchHTTPHeaders(
                    ctx.getHttpClient(), crawlData.getReference());
            if (headers == null) {
                crawlData.setState(HttpCrawlState.REJECTED);
                return false;
            }
            metadata.putAll(headers);
            
            HttpImporterPipelineUtil.enhanceHTTPHeaders(metadata);
            HttpImporterPipelineUtil.applyMetadataToDocument(ctx.getDocument());
            
            ctx.getCrawler().fireCrawlerEvent(
                    HttpCrawlerEvent.DOCUMENT_METADATA_FETCHED, 
                    ctx.getCrawlData(), headersFetcher);
            return true;
        }
    }
    
    //--- HTTP Headers Filters -------------------------------------------------
    private static class HttpMetadataFiltersHEADStage 
            extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            if (ctx.getHttpHeadersFetcher() != null 
                    && ImporterPipelineUtil.isHeadersRejected(ctx)) {
                ctx.getCrawlData().setState(HttpCrawlState.REJECTED);
                return false;
            }
            return true;
        }
    }

    //--- HTTP Headers Canonical URL handling ----------------------------------
    private static class HttpMetadataCanonicalHEADStage 
            extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            // Return right away if http headers are not fetched
            if (!ctx.isHttpHeadFetchEnabled()) {
                return true;
            }
            return HttpImporterPipelineUtil.resolveCanonical(ctx, true);
        }
    }

    //--- Document Fetcher -----------------------------------------------------
    //TODO getting big: move to its own class.
    private static class DocumentFetcherStage extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            HttpCrawlData crawlData = ctx.getCrawlData();
            
            //TODO for now we assume the document is downloadable.
            // download as file
            HttpFetchResponse response =
                    ctx.getConfig().getDocumentFetcher().fetchDocument(
                            ctx.getHttpClient(), ctx.getDocument());

            HttpImporterPipelineUtil.enhanceHTTPHeaders(
                    ctx.getDocument().getMetadata());
            HttpImporterPipelineUtil.applyMetadataToDocument(ctx.getDocument());
            
            //TODO Fix #17. Put in place a more permanent solution to this line
            boolean redirected = TargetURLRedirectStrategy.fixRedirectURL(
                    ctx.getHttpClient(), ctx.getDocument(), 
                    crawlData, ctx.getCrawlDataStore());
            //--- END Fix #17 ---

            //--- Fix #135. Make sure redirect is not already handled. ---
            Set<String> activeRefs = 
                    ctx.getCrawler().getTransientActiveReferences();
            String ref = crawlData.getReference();
            synchronized (activeRefs) {
                if (activeRefs.contains(ref)) {
                    rejectRedirectDup("being processed", ctx, response);
                    return false;
                }
                if (redirected) {
                    ICrawlDataStore store = ctx.getCrawlDataStore();
                    //TODO throw an event if already active/processed(ing)?
                    if (store.isActive(ref)) {
                        rejectRedirectDup("being processed", ctx, response);
                        return false;
                    } else if (store.isQueued(ref)) {
                        rejectRedirectDup("queued", ctx, response);
                        return false;
                    } else if (store.isProcessed(ref)) {
                        rejectRedirectDup("processed", ctx, response);
                        return false;
                    } else {
                        LOG.debug("Adding transient active reference: " + ref);
                        activeRefs.add(ref);
                    }
                }
            }
            //--- End Fix #135 ---
            
            CrawlState state = response.getCrawlState();
            crawlData.setState(state);
            if (state.isGoodState()) {
                ctx.fireCrawlerEvent(HttpCrawlerEvent.DOCUMENT_FETCHED, 
                        crawlData, response);
            } else {
                String eventType = null;
                if (state.isOneOf(HttpCrawlState.NOT_FOUND)) {
                    eventType = HttpCrawlerEvent.REJECTED_NOTFOUND;
                } else {
                    eventType = HttpCrawlerEvent.REJECTED_BAD_STATUS;
                }
                ctx.fireCrawlerEvent(eventType, crawlData, response);
                return false;
            }
            return true;
        }
    }

    private static void rejectRedirectDup(String action, 
            HttpImporterPipelineContext ctx, HttpFetchResponse response) {
        HttpCrawlData crawlData = ctx.getCrawlData();
        crawlData.setState(HttpCrawlState.DUPLICATE);
        String eventType = HttpCrawlerEvent.REJECTED_DUPLICATE;
        ctx.fireCrawlerEvent(eventType, crawlData, response);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Redirect target URL is already " + action
                    + ": " + crawlData.getReference()
                    + " (from: " + crawlData.getOriginalReference() + ").");
        }
    }   
    
    //--- HTTP Headers Canonical URL after fetch -------------------------------
    private static class HttpMetadataCanonicalGETStage 
            extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            return HttpImporterPipelineUtil.resolveCanonical(ctx, true);
        }
    }

    //--- Document Canonical URL from <head> -----------------------------------
    private static class DocumentCanonicalStage 
            extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            return HttpImporterPipelineUtil.resolveCanonical(ctx, false);
        }
    }
    
    //--- Robots Meta Creation -------------------------------------------------
    private static class RobotsMetaCreateStage extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            if (ctx.getConfig().isIgnoreRobotsMeta()) {
                return true;
            }

            try {
                Reader reader = ctx.getContentReader();
                ctx.setRobotsMeta(
                        ctx.getConfig().getRobotsMetaProvider().getRobotsMeta(
                                reader, ctx.getCrawlData().getReference(),
                                ctx.getDocument().getContentType(),
                                ctx.getMetadata()));
                reader.close();

                ctx.getCrawler().fireCrawlerEvent(
                        HttpCrawlerEvent.CREATED_ROBOTS_META, 
                        ctx.getCrawlData(), 
                        ctx.getRobotsMeta());
            } catch (IOException e) {
                throw new CollectorException("Cannot create RobotsMeta for : " 
                                + ctx.getCrawlData().getReference(), e);
            }
            return true;
        }
    }

    //--- Robots Meta NoIndex Check --------------------------------------------
    private static class RobotsMetaNoIndexStage extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            boolean canIndex = ctx.getConfig().isIgnoreRobotsMeta() 
                    || ctx.getRobotsMeta() == null
                    || !ctx.getRobotsMeta().isNoindex();
            if (!canIndex) {
                
                ctx.getCrawler().fireCrawlerEvent(
                        HttpCrawlerEvent.REJECTED_ROBOTS_META_NOINDEX, 
                        ctx.getCrawlData(), 
                        ctx.getRobotsMeta());
                ctx.getCrawlData().setState(HttpCrawlState.REJECTED);
                return false;
            }
            return canIndex;
        }
    }
    
    //--- Headers filters if not done already ----------------------------------
    private static class HttpMetadataFiltersGETStage 
            extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            if (ctx.getHttpHeadersFetcher() == null) {
                if (ImporterPipelineUtil.isHeadersRejected(ctx)) {
                    ctx.getCrawlData().setState(HttpCrawlState.REJECTED);
                    return false;
                }
            }
            return true;
        }
    }    
    
    //--- Document Pre-Processing ----------------------------------------------
    private static class DocumentPreProcessingStage 
            extends AbstractImporterStage {
        @Override
        public boolean executeStage(HttpImporterPipelineContext ctx) {
            if (ctx.getConfig().getPreImportProcessors() != null) {
                for (IHttpDocumentProcessor preProc :
                        ctx.getConfig().getPreImportProcessors()) {
                    preProc.processDocument(
                            ctx.getHttpClient(), ctx.getDocument());
                    
                    ctx.getCrawler().fireCrawlerEvent(
                            HttpCrawlerEvent.DOCUMENT_PREIMPORTED, 
                            ctx.getCrawlData(), preProc);
                }
            }
            return true;
        }
    }
}
