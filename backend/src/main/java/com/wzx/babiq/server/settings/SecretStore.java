package com.wzx.babiq.server.settings;

import java.util.Optional;

/**
 * 明文密钥存储抽象。
 *
 * <p>Provider 表只能保存 `secretRef`，不能保存明文 API Key。SecretStore 负责把“明文密钥”
 * 和“可持久化引用”隔离开，P2-3 可以把实现替换为 Windows Credential Manager 或 Java KeyStore。</p>
 */
public interface SecretStore {

    /**
     * 保存一段明文密钥，并返回外部可持久化引用。
     *
     * @param namespace 密钥命名空间，例如 provider.deepseek
     * @param secretPlainText 明文密钥，只能在 SecretStore 内部短暂出现
     * @return 可写入数据库的 secretRef
     */
    String save(String namespace, String secretPlainText);

    /**
     * 按引用读取明文密钥。
     *
     * @param secretRef `save` 返回的引用
     * @return 找到时返回明文；不存在时返回空
     */
    Optional<String> load(String secretRef);

    /**
     * 删除引用对应的密钥。
     *
     * @param secretRef `save` 返回的引用
     */
    void delete(String secretRef);
}
