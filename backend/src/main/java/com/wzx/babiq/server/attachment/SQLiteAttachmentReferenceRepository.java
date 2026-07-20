package com.wzx.babiq.server.attachment;

import com.wzx.babiq.server.persistence.mapper.ItemMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis-backed attachment-reference projection for the local business database.
 */
@Repository
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public class SQLiteAttachmentReferenceRepository implements AttachmentReferenceRepository {

    private final ItemMapper itemMapper;

    public SQLiteAttachmentReferenceRepository(ItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    @Override
    public List<AttachmentReferenceRecord> findAll() {
        return List.copyOf(itemMapper.selectAttachmentReferenceRecords());
    }
}
