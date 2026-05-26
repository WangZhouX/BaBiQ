package com.wzx.babiq.server.memory.artifact;

import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;

import java.util.List;

/**
 * Markdown 镜像生成结果。
 *
 * @param artifacts 本次写入的产物记录，调用方负责保存到 SQLite
 */
public record MemoryArtifactMirrorResult(List<MemoryArtifactRecord> artifacts) {
}
