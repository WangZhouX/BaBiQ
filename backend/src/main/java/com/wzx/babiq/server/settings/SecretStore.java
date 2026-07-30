package com.wzx.babiq.server.settings;

import java.util.List;
import java.util.Optional;

/**
 * 明文密钥存储抽象。
 *
 * <p>Provider 表只能保存 `secretRef`，不能保存明文 API Key。SecretStore 负责把“明文密钥”
 * 和“可持久化引用”隔离开，P2-3 可以把实现替换为 Windows Credential Manager 或 Java KeyStore。</p>
 */
public interface SecretStore {

    /**
     * 预分配一个尚未写入敏感材料的不透明引用。
     *
     * <p>兼容实现默认 fail closed；需要 reserve-before-save 的生产实现必须显式覆写。</p>
     */
    default String allocateRef(String namespace) {
        throw new UnsupportedOperationException("当前 SecretStore 不支持预分配引用");
    }

    /**
     * 将字符材料写入预分配引用；实现必须拒绝覆盖任何已有 entry。
     *
     * <p>兼容实现默认 fail closed，不能用旧的随机引用 save API 模拟显式引用写入。</p>
     */
    default void saveCharsAtRef(String secretRef, char[] secretChars) {
        throw new UnsupportedOperationException("当前 SecretStore 不支持显式引用写入");
    }

    /**
     * 返回 sanitized namespace 前缀下的引用，结果必须稳定排序且不得返回其他命名空间。
     */
    default List<String> listRefs(String namespacePrefix) {
        throw new UnsupportedOperationException("当前 SecretStore 不支持引用枚举");
    }

    /**
     * 以字符数组保存敏感材料。新认证链路应优先使用此 API，避免把凭据建模为长期存活的 String。
     *
     * @implSpec 默认实现仅供 legacy/test adapter 兼容，会创建不可擦除的 String；生产 SecretStore
     * 必须覆写本方法并直接处理 char[]。
     */
    default String saveChars(String namespace, char[] secretChars) {
        if (secretChars == null || secretChars.length == 0) {
            throw new IllegalArgumentException("secretChars 不能为空");
        }
        String value = new String(secretChars);
        return save(namespace, value);
    }

    /**
     * 按引用读取字符数组；调用方负责在 finally 中擦除返回数组。
     *
     * @implSpec 默认实现仅供 legacy/test adapter 兼容，会经过不可擦除的 String；生产 SecretStore
     * 必须覆写本方法并直接返回可由调用方擦除的 char[]。
     */
    default Optional<char[]> loadChars(String secretRef) {
        return load(secretRef).map(String::toCharArray);
    }

    /** 按引用读取字符数组，找不到时抛出不泄露密钥内容的异常。 */
    default char[] requireChars(String secretRef) {
        return loadChars(secretRef)
                .orElseThrow(() -> new SecretStoreException(
                        "SECRET_STORE_REFERENCE_NOT_FOUND", "密钥不存在"));
    }

    /**
     * 保存一段明文密钥，并返回外部可持久化引用。
     *
     * <p>如果本方法抛出异常，调用前已有的密钥和底层持久化文件必须保持不变。</p>
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
     * 按引用读取明文密钥，读取不到时直接抛出明确异常。
     *
     * <p>Provider 构建 ChatClient 时不能静默降级为空 key，否则真实调用失败会变成更难排查的
     * 401/403。本方法把“密钥不存在”提前暴露成 BaBiQ 自己的配置错误。</p>
     *
     * @param secretRef `save` 返回的引用
     * @return 明文密钥
     */
    default String require(String secretRef) {
        return load(secretRef)
                .orElseThrow(() -> new SecretStoreException(
                        "SECRET_STORE_REFERENCE_NOT_FOUND", "密钥不存在"));
    }

    /**
     * 删除引用对应的密钥。
     *
     * <p>实现必须提供失败原子性：如果本方法抛出异常，调用前可读取的原 entry 和底层持久化文件
     * 必须保持不变。上层 Provider 补偿逻辑会在删除失败后重新引用旧 secretRef，不能接受
     * “已经删除但仍抛异常”的不确定状态。</p>
     *
     * @param secretRef `save` 返回的引用
     */
    void delete(String secretRef);
}
