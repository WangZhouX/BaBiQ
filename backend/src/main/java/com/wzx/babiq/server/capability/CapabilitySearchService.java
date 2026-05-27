package com.wzx.babiq.server.capability;

/**
 * 能力搜索服务端口。
 *
 * <p>默认实现是 P3-5a 接入的 Spring AI Community Lucene 搜索器。接口保留下来是为了让
 * ToolSearchTool、Planner 和 JSON-RPC handler 只依赖 BaBiQ 端口，不直接绑定具体索引实现。</p>
 */
public interface CapabilitySearchService {

    /**
     * 搜索与请求最相关的能力。
     *
     * @param request 搜索请求
     * @return 搜索结果
     */
    CapabilitySearchResult search(CapabilitySearchRequest request);
}
