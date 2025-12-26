package org.engine.pickerengine.repository;

import org.engine.pickerengine.entity.InstagramPostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstagramPostRepository extends JpaRepository<InstagramPostEntity, String> {

    List<InstagramPostEntity> findByUsername(String username);

    void deleteByUsername(String username);
}
