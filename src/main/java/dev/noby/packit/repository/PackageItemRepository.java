package dev.noby.packit.repository;

import dev.noby.packit.entity.PackageItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageItemRepository extends JpaRepository<PackageItemEntity, Long> {
}
