package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.File;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FileRepository extends MongoRepository<File, String> {
    @Cacheable(cacheNames = "fileByName", key = "#filename")
    Optional<File> findByFilename(String filename);
}
