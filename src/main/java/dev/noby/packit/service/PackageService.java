package dev.noby.packit.service;

import dev.noby.packit.dto.*;
import dev.noby.packit.entity.PackageEntity;
import dev.noby.packit.entity.PackageItemEntity;
import dev.noby.packit.entity.PackageStatus;
import dev.noby.packit.exception.InvalidPackageStatusTransitionException;
import dev.noby.packit.exception.PackageItemNotFoundException;
import dev.noby.packit.exception.PackageNotFoundException;
import dev.noby.packit.mapper.PackageMapper;
import dev.noby.packit.outbox.OutboxMessageService;
import dev.noby.packit.outbox.OutboxMessageService.DocumentType;
import dev.noby.packit.outbox.OutboxMessageService.HistoryType;
import dev.noby.packit.repository.PackageItemRepository;
import dev.noby.packit.repository.PackageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class PackageService {

    private final PackageRepository packageRepository;
    private final PackageItemRepository packageItemRepository;
    private final PackageMapper packageMapper;
    private final OutboxMessageService outboxMessageService;

    public PackageService(PackageRepository packageRepository, PackageItemRepository packageItemRepository,
                          PackageMapper packageMapper,
                          OutboxMessageService outboxMessageService) {
        this.packageRepository = packageRepository;
        this.packageItemRepository = packageItemRepository;
        this.packageMapper = packageMapper;
        this.outboxMessageService = outboxMessageService;
    }

    public List<PackageResponse> getPackages(PackageStatus status) {
        List<PackageEntity> packages = status == null
                ? packageRepository.findAllByOrderByCreatedAtDesc()
                : packageRepository.findByStatusOrderByCreatedAtDesc(status);
        return packages.stream().map(packageMapper::toResponse).toList();
    }

    public PackageResponse getPackage(Long id) {
        return packageMapper.toResponse(findPackage(id));
    }

    @Transactional
    public PackageResponse createPackage(PackageCreateRequest request) {
        PackageEntity entity = new PackageEntity(request.name().trim(), normalize(request.reference()),
                normalize(request.description()));
        packageRepository.save(entity);
        Map<String, Object> after = outboxMessageService.packageDetails(entity);
        after.put("status", entity.getStatus().name());
        outboxMessageService.recordHistory(entity, HistoryType.PACKAGE_CREATED, null, after);
        return packageMapper.toResponse(entity);
    }

    @Transactional
    public PackageResponse updatePackage(Long id, PackageUpdateRequest request) {
        PackageEntity entity = findPackage(id);
        ensureNotArchived(entity);
        Map<String, Object> before = outboxMessageService.packageDetails(entity);
        entity.updateDetails(request.name().trim(), normalize(request.reference()), normalize(request.description()));
        outboxMessageService.recordHistory(entity, HistoryType.PACKAGE_UPDATED, before,
                outboxMessageService.packageDetails(entity));
        return packageMapper.toResponse(entity);
    }

    @Transactional
    public PackageResponse changeStatus(Long id, PackageStatus requestedStatus) {
        PackageEntity entity = findPackage(id);
        PackageStatus currentStatus = entity.getStatus();
        if (!isAllowedTransition(currentStatus, requestedStatus)) {
            throw new InvalidPackageStatusTransitionException(currentStatus, requestedStatus);
        }
        entity.changeStatus(requestedStatus);
        outboxMessageService.recordHistory(entity, HistoryType.PACKAGE_STATUS_CHANGED,
                Map.of("status", currentStatus.name()), Map.of("status", requestedStatus.name()));
        if (requestedStatus == PackageStatus.PREPARING) {
            outboxMessageService.recordDocument(entity, DocumentType.INTAKE);
        } else if (requestedStatus == PackageStatus.READY) {
            outboxMessageService.recordDocument(entity, DocumentType.DISPATCH);
        }
        return packageMapper.toResponse(entity);
    }

    @Transactional
    public PackageItemResponse addItem(Long packageId, PackageItemRequest request) {
        PackageEntity packageEntity = findPackage(packageId);
        ensureNotArchived(packageEntity);
        PackageItemEntity item = new PackageItemEntity(normalize(request.sku()), request.productName().trim(),
                request.quantity());
        packageEntity.addItem(item);
        packageItemRepository.saveAndFlush(item);
        outboxMessageService.recordHistory(packageEntity, HistoryType.PACKAGE_ITEM_ADDED, null,
                outboxMessageService.itemDetails(item));
        return packageMapper.toItemResponse(item);
    }

    @Transactional
    public PackageItemResponse updateItem(Long packageId, Long itemId, PackageItemRequest request) {
        PackageEntity packageEntity = findPackage(packageId);
        ensureNotArchived(packageEntity);
        PackageItemEntity item = packageEntity.findItem(itemId)
                .orElseThrow(() -> new PackageItemNotFoundException(packageId, itemId));
        Map<String, Object> before = outboxMessageService.itemDetails(item);
        item.update(normalize(request.sku()), request.productName().trim(), request.quantity());
        outboxMessageService.recordHistory(packageEntity, HistoryType.PACKAGE_ITEM_UPDATED, before,
                outboxMessageService.itemDetails(item));
        return packageMapper.toItemResponse(item);
    }

    public List<PackageItemResponse> getItems(Long packageId) {
        return findPackage(packageId).getItems().stream().map(packageMapper::toItemResponse).toList();
    }

    public DashboardSummaryResponse getDashboardSummary() {
        Map<String, Long> countsByStatus = new LinkedHashMap<>();
        for (PackageStatus status : PackageStatus.values()) {
            countsByStatus.put(status.name(), packageRepository.countByStatus(status));
        }
        return new DashboardSummaryResponse(packageRepository.count(), countsByStatus);
    }

    private PackageEntity findPackage(Long id) {
        return packageRepository.findById(id).orElseThrow(() -> new PackageNotFoundException(id));
    }

    private void ensureNotArchived(PackageEntity entity) {
        if (entity.getStatus() == PackageStatus.ARCHIVED) {
            throw new InvalidPackageStatusTransitionException(PackageStatus.ARCHIVED, entity.getStatus());
        }
    }

    private boolean isAllowedTransition(PackageStatus current, PackageStatus requested) {
        if (current == requested || current == PackageStatus.ARCHIVED) {
            return false;
        }
        if (requested == PackageStatus.ARCHIVED) {
            return true;
        }
        return switch (current) {
            case RECEIVED -> requested == PackageStatus.PREPARING;
            case PREPARING -> requested == PackageStatus.READY;
            case READY -> requested == PackageStatus.IN_TRANSIT;
            case IN_TRANSIT -> requested == PackageStatus.DELIVERED;
            case DELIVERED, ARCHIVED -> false;
        };
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
