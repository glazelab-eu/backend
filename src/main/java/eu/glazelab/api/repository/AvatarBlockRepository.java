package eu.glazelab.api.repository;

import eu.glazelab.api.model.AvatarBlock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AvatarBlockRepository extends JpaRepository<AvatarBlock, UUID> {
    boolean existsByUploadSha256AndStoredSha256(String uploadSha256, String storedSha256);

    interface Hashes { String getUploadSha256(); String getStoredSha256(); long getDhash(); }

    /** Every block, compared with an upload in memory (the dHash distance needs every row; one statement). */
    @Query("select b.uploadSha256 as uploadSha256, b.storedSha256 as storedSha256, b.dhash as dhash from AvatarBlock b")
    List<Hashes> hashes();
}
