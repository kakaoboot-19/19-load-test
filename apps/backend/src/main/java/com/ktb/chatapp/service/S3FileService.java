package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.util.FileUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3FileService implements FileService {

    private final S3Client s3Client;
    private final FileRepository fileRepository;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.base-directory:uploads}")
    private String baseDirectory;


    // ---------------------------------------------------------
    // 1. 업로드
    // ---------------------------------------------------------
    @Override
    public FileUploadResult uploadFile(MultipartFile file, String uploaderId) {
        try {
            // 파일 검증
            FileUtil.validateFile(file);

            // 파일명 처리
            String originalFilename = FileUtil.normalizeOriginalFilename(file.getOriginalFilename());
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);

            // S3 key 경로
            String key = baseDirectory + "/" + safeFileName;

            // S3 업로드
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );

            log.info("S3 업로드 완료: {}", key);

            // 메타데이터 DB 기록
            File fileEntity = File.builder()
                    .filename(safeFileName)
                    .originalname(originalFilename)
                    .mimetype(file.getContentType())
                    .size(file.getSize())
                    .path(key)
                    .user(uploaderId)
                    .uploadDate(LocalDateTime.now())
                    .build();

            File savedFile = fileRepository.save(fileEntity);

            return FileUploadResult.builder()
                    .success(true)
                    .file(savedFile)
                    .build();

        } catch (Exception e) {
            log.error("S3 파일 업로드 실패: {}", e.getMessage(), e);
            throw new RuntimeException("S3 파일 업로드 실패: " + e.getMessage());
        }
    }


    // ---------------------------------------------------------
    // 2. storeFile (프로필 이미지 등 서브 디렉토리 저장)
    // ---------------------------------------------------------
    @Override
    public String storeFile(MultipartFile file, String subDirectory) {
        try {
            FileUtil.validateFile(file);

            String originalFilename = FileUtil.normalizeOriginalFilename(file.getOriginalFilename());
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);

            String key = baseDirectory + "/" + subDirectory + "/" + safeFileName;

            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );

            log.info("S3 파일 저장 완료: {}", key);

            // API 경로 또는 CDN URL 반환할 수 있음
            return key;

        } catch (IOException e) {
            throw new RuntimeException("storeFile 실패: " + e.getMessage(), e);
        }
    }


    // ---------------------------------------------------------
    // 3. loadFileAsResource (파일 다운로드 권한 검증)
    // ---------------------------------------------------------
    @Override
    public Resource loadFileAsResource(String fileName, String requesterId) {
        try {
            // 파일 조회
            File fileEntity = fileRepository.findByFilename(fileName)
                    .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileName));

            // 메시지 소유권 검증
            Message message = messageRepository.findByFileId(fileEntity.getId())
                    .orElseThrow(() -> new RuntimeException("파일과 연결된 메시지가 없습니다."));

            // 방 참가자 검증
            Room room = roomRepository.findById(message.getRoomId())
                    .orElseThrow(() -> new RuntimeException("방을 찾을 수 없습니다."));

            if (!room.getParticipantIds().contains(requesterId)) {
                throw new RuntimeException("파일 접근 권한이 없습니다.");
            }

            // S3 다운로드
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(fileEntity.getPath())
                    .build();

            ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);

            log.info("S3 파일 로드 성공: {}", fileEntity.getPath());

            return new InputStreamResource(s3Object);

        } catch (Exception e) {
            log.error("S3 파일 로드 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일을 로드할 수 없습니다: " + fileName, e);
        }
    }


    // ---------------------------------------------------------
    // 4. 파일 삭제
    // ---------------------------------------------------------
    @Override
    public boolean deleteFile(String fileId, String requesterId) {
        try {
            File fileEntity = fileRepository.findById(fileId)
                    .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

            // 삭제 권한 체크 (업로더만 삭제 가능)
            if (!fileEntity.getUser().equals(requesterId)) {
                throw new RuntimeException("파일 삭제 권한이 없습니다.");
            }

            // S3 삭제
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucket)
                            .key(fileEntity.getPath())
                            .build()
            );

            log.info("S3 파일 삭제 완료: {}", fileEntity.getPath());

            // DB 삭제
            fileRepository.delete(fileEntity);

            return true;

        } catch (Exception e) {
            log.error("파일 삭제 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일 삭제 실패: " + e.getMessage(), e);
        }
    }
}
