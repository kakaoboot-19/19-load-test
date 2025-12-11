package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.CreateRoomRequest;
import com.ktb.chatapp.dto.HealthResponse;
import com.ktb.chatapp.dto.PageMetadata;
import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    // Redis 캐시 관리용
    private final CacheManager cacheManager;

    /**
     * 방 목록 페이징 조회
     */
    public RoomsResponse getAllRoomsWithPagination(
            com.ktb.chatapp.dto.PageRequest pageRequest, String name) {

        try {
            // 정렬 설정 검증
            if (!pageRequest.isValidSortField()) {
                pageRequest.setSortField("createdAt");
            }
            if (!pageRequest.isValidSortOrder()) {
                pageRequest.setSortOrder("desc");
            }

            // 정렬 방향 설정
            Sort.Direction direction = "desc".equals(pageRequest.getSortOrder())
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;

            // 정렬 필드 매핑 (participantsCount는 특별 처리 필요)
            String sortField = pageRequest.getSortField();
            if ("participantsCount".equals(sortField)) {
                sortField = "participantIds"; // MongoDB 필드명으로 변경
            }

            // Pageable 객체 생성
            PageRequest springPageRequest = PageRequest.of(
                    pageRequest.getPage(),
                    pageRequest.getPageSize(),
                    Sort.by(direction, sortField)
            );

            // 검색어가 있는 경우와 없는 경우 분리
            Page<Room> roomPage;
            if (pageRequest.getSearch() != null && !pageRequest.getSearch().trim().isEmpty()) {
                roomPage = roomRepository.findByNameContainingIgnoreCase(
                        pageRequest.getSearch().trim(), springPageRequest);
            } else {
                roomPage = roomRepository.findAll(springPageRequest);
            }

            // Room을 RoomResponse로 변환
            List<RoomResponse> roomResponses = roomPage.getContent().stream()
                    .map(room -> mapToRoomResponse(room, name))
                    .collect(Collectors.toList());

            // 메타데이터 생성
            PageMetadata metadata = PageMetadata.builder()
                    .total(roomPage.getTotalElements())
                    .page(pageRequest.getPage())
                    .pageSize(pageRequest.getPageSize())
                    .totalPages(roomPage.getTotalPages())
                    .hasMore(roomPage.hasNext())
                    .currentCount(roomResponses.size())
                    .sort(PageMetadata.SortInfo.builder()
                            .field(pageRequest.getSortField())
                            .order(pageRequest.getSortOrder())
                            .build())
                    .build();

            return RoomsResponse.builder()
                    .success(true)
                    .data(roomResponses)
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                    .success(false)
                    .data(List.of())
                    .build();
        }
    }

    /**
     * Health Check
     */
    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();

            // MongoDB 연결 상태 확인
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                // 간단한 쿼리로 연결 상태 및 지연 시간 측정
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            // 최근 활동 조회
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            // 서비스 상태 정보 구성
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                    .connected(isMongoConnected)
                    .latency(latency)
                    .build());

            return HealthResponse.builder()
                    .success(true)
                    .services(services)
                    .lastActivity(lastActivity)
                    .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                    .success(false)
                    .services(new HashMap<>())
                    .build();
        }
    }

    /**
     * 방 생성
     */
    @CacheEvict(cacheNames = "roomById", allEntries = true)
    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);

        // 새로 만든 방은 메시지가 없으니 recentMessageCount 캐시를 0으로 세팅(선택)
        putToCache("recentMessageCount", savedRoom.getId(), 0L);

        // Publish event for room created
        try {
            RoomResponse roomResponse = mapToRoomResponse(savedRoom, name);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }

        return savedRoom;
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    /**
     * 단일 방 조회 + 캐시
     * - cacheName: roomById
     * - key: roomId
     */
    @Cacheable(cacheNames = "roomById", key = "#roomId")
    public Room getRoom(String roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("방을 찾을 수 없습니다: " + roomId));
    }

    /**
     * 방 입장
     */
    @CacheEvict(cacheNames = "roomById", key = "#roomId")
    public Room joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        // 비밀번호 확인
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        boolean joined = room.addParticipant(user.getId());

        if (joined) { // 실제 입장했을 때만
            log.info("Atomic Update 성공: 신규 유저 입장");

            // 채팅방 참여
            room = roomRepository.save(room);

            // 여기서 메시지 수는 변하지 않으므로 recentMessageCount 캐시는 건드리지 않음

            // Publish event for room updated
            try {
                RoomResponse roomResponse = mapToRoomResponse(room, name);
                eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
            } catch (Exception e) {
                log.error("roomUpdate 이벤트 발행 실패", e);
            }

        }

        return room;
    }

    /**
     * Room → RoomResponse 매핑 + recentMessageCount 캐시 사용
     */
    private RoomResponse mapToRoomResponse(Room room, String name) {
        if (room == null) return null;

        User creator = null;
        if (room.getCreator() != null) {
            creator = userRepository.findById(room.getCreator()).orElse(null);
        }

        List<User> participants = room.getParticipantIds().stream()
                .map(userRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        // 최근 10분간 메시지 수 조회 → Redis 캐시 사용
        long recentMessageCount = getRecentMessageCount(room.getId());

        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword())
                .creator(creator != null ? UserResponse.builder()
                        .id(creator.getId())
                        .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                        .email(creator.getEmail() != null ? creator.getEmail() : "")
                        .build() : null)
                .participants(participants.stream()
                        .filter(p -> p != null && p.getId() != null)
                        .map(p -> UserResponse.builder()
                                .id(p.getId())
                                .name(p.getName() != null ? p.getName() : "알 수 없음")
                                .email(p.getEmail() != null ? p.getEmail() : "")
                                .build())
                        .collect(Collectors.toList()))
                .createdAtDateTime(room.getCreatedAt())
                .isCreator(creator != null && creator.getId().equals(name))
                .recentMessageCount((int) recentMessageCount)
                .build();
    }

    // =========================
    // recentMessageCount 캐시 로직
    // =========================

    /**
     * 최근 10분 메시지 수를 Redis 캐시에 태워서 반환
     * - 캐시 키: roomId
     * - 캐시 이름: recentMessageCount
     */
    public long getRecentMessageCount(String roomId) {
        // 1차: 캐시 조회
        Long cached = getFromCache("recentMessageCount", roomId, Long.class);
        if (cached != null) {
            return cached;
        }

        // 2차: MongoDB 조회
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
        long count = 0L;
        try {
            count = messageRepository.countRecentMessagesByRoomId(roomId, tenMinutesAgo);
        } catch (Exception e) {
            log.error("최근 메시지 수 조회 실패 - roomId: {}", roomId, e);
        }

        // 3차: Redis 캐시에 저장
        putToCache("recentMessageCount", roomId, count);

        return count;
    }

    /**
     * 최근 메시지 수 캐시를 1 증가
     * - 완벽히 원자적이진 않지만, 부하테스트/E2E 수준에서는 충분
     */
    public void incrementRecentMessageCount(String roomId) {
        try {
            Long current = getFromCache("recentMessageCount", roomId, Long.class);
            if (current == null) {
                // 캐시에 아직 없으면 실제 값 계산 후 +1
                long counted = getRecentMessageCount(roomId);
                putToCache("recentMessageCount", roomId, counted + 1);
            } else {
                putToCache("recentMessageCount", roomId, current + 1);
            }
        } catch (Exception e) {
            // 캐시 장애나도 기능은 동작해야 하니까, 그냥 로그만 찍고 무시
            log.debug("incrementRecentMessageCount failed for roomId={}", roomId, e);
        }
    }

    // =========================
    // Cache 헬퍼 메서드
    // =========================

    private <T> T getFromCache(String cacheName, Object key, Class<T> type) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                return null;
            }
            Cache.ValueWrapper wrapper = cache.get(key);
            if (wrapper == null) {
                return null;
            }
            Object value = wrapper.get();
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            return null;
        } catch (Exception e) {
            log.debug("캐시 조회 실패 - cache: {}, key: {}", cacheName, key, e);
            return null;
        }
    }

    private void putToCache(String cacheName, Object key, Object value) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null && value != null) {
                cache.put(key, value);
            }
        } catch (Exception e) {
            log.debug("캐시 저장 실패 - cache: {}, key: {}", cacheName, key, e);
        }
    }
}
