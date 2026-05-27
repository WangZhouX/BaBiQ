package com.wzx.babiq.server.capability;

/**
 * 能力搜索服务端口。
 *
 * <p>本阶段默认实现是轻量词法搜索；接口保留下来是为了后续可以把 Lucene、
 * Spring AI Community Tool Search 或 VectorStore 作为候选实现接进来。</p>
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
