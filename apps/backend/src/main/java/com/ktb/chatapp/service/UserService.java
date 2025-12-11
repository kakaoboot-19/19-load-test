package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.ProfileImageResponse;
import com.ktb.chatapp.dto.UpdatePasswordRequest;
import com.ktb.chatapp.dto.UpdateProfileRequest;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final FileService fileService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.profile.image.max-size:5242880}") // 5MB
    private long maxProfileImageSize;

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp"
    );

    // =========================
    // 조회 메서드 (캐시 사용)
    // =========================

    /**
     * 현재 사용자 프로필 조회 (email 기반)
     */
    @Cacheable(cacheNames = "userByEmail", key = "#email.toLowerCase()")
    public UserResponse getCurrentUserProfile(String email) {
        String normalizedEmail = email.toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        return UserResponse.from(user);
    }

    /**
     * 특정 사용자 프로필 조회 (userId 기반)
     */
    @Cacheable(cacheNames = "userById", key = "#userId")
    public UserResponse getUserProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        return UserResponse.from(user);
    }

    // =========================
    // 수정 메서드 (캐시 무효화)
    //  - 단순하게 user 관련 캐시 전체 비우기
    // =========================

    /**
     * 사용자 프로필 업데이트
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "userByEmail", allEntries = true),
            @CacheEvict(cacheNames = "userById", allEntries = true)
    })
    public UserResponse updateUserProfile(String email, UpdateProfileRequest request) {
        String normalizedEmail = email.toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        user.setName(request.getName());
        user.setUpdatedAt(LocalDateTime.now());

        User updatedUser = userRepository.save(user);
        log.info("사용자 프로필 업데이트 완료 - ID: {}, Name: {}", updatedUser.getId(), request.getName());

        return UserResponse.from(updatedUser);
    }

    /**
     * 사용자 비밀번호 업데이트
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "userByEmail", allEntries = true),
            @CacheEvict(cacheNames = "userById", allEntries = true)
    })
    public UserResponse updateUserPassword(String email, UpdatePasswordRequest request) {
        String normalizedEmail = email.toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        // TODO: 현재 비밀번호 검증 로직 다시 살릴지 여부는 나중에 결정
        // if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
        //     throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다.");
        // }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdatedAt(LocalDateTime.now());

        User updatedUser = userRepository.save(user);
        log.info("사용자 비밀번호 업데이트 완료 - ID: {}", updatedUser.getId());

        return UserResponse.from(updatedUser);
    }

    /**
     * 프로필 이미지 업로드
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "userByEmail", allEntries = true),
            @CacheEvict(cacheNames = "userById", allEntries = true)
    })
    public ProfileImageResponse uploadProfileImage(String email, MultipartFile file) {
        String normalizedEmail = email.toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        validateProfileImageFile(file);

        if (user.getProfileImage() != null && !user.getProfileImage().isEmpty()) {
            deleteOldProfileImage(user.getProfileImage());
        }

        String profileImageUrl = fileService.storeFile(file, "profiles");

        user.setProfileImage(profileImageUrl);
        user.setUpdatedAt(LocalDateTime.now());
        User updatedUser = userRepository.save(user);

        log.info("프로필 이미지 업로드 완료 - User ID: {}, File: {}", updatedUser.getId(), profileImageUrl);

        return new ProfileImageResponse(
                true,
                "프로필 이미지가 업데이트되었습니다.",
                profileImageUrl
        );
    }

    /**
     * 프로필 이미지 삭제
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "userByEmail", allEntries = true),
            @CacheEvict(cacheNames = "userById", allEntries = true)
    })
    public void deleteProfileImage(String email) {
        String normalizedEmail = email.toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        if (user.getProfileImage() != null && !user.getProfileImage().isEmpty()) {
            deleteOldProfileImage(user.getProfileImage());
            user.setProfileImage("");
            user.setUpdatedAt(LocalDateTime.now());
            User updatedUser = userRepository.save(user);
            log.info("프로필 이미지 삭제 완료 - User ID: {}", updatedUser.getId());
        }
    }

    /**
     * 회원 탈퇴 처리
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "userByEmail", allEntries = true),
            @CacheEvict(cacheNames = "userById", allEntries = true)
    })
    public void deleteUserAccount(String email) {
        String normalizedEmail = email.toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        if (user.getProfileImage() != null && !user.getProfileImage().isEmpty()) {
            deleteOldProfileImage(user.getProfileImage());
        }

        userRepository.delete(user);
        log.info("회원 탈퇴 완료 - User ID: {}", user.getId());
    }

    // =========================
    // 파일/이미지 관련 내부 메서드
    // =========================

    private void validateProfileImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("이미지가 제공되지 않았습니다.");
        }

        if (file.getSize() > maxProfileImageSize) {
            throw new IllegalArgumentException("파일 크기는 5MB를 초과할 수 없습니다.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }

        String extension = FileUtil.getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }
    }

    private void deleteOldProfileImage(String profileImageUrl) {
        try {
            if (profileImageUrl != null && profileImageUrl.startsWith("/uploads/")) {
                String filename = profileImageUrl.substring("/uploads/".length());
                Path filePath = Paths.get(uploadDir, filename);

                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                    log.info("기존 프로필 이미지 삭제 완료: {}", filename);
                }
            }
        } catch (IOException e) {
            log.warn("기존 프로필 이미지 삭제 실패: {}", e.getMessage());
        }
    }
}
