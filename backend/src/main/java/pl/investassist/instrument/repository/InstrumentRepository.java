package pl.investassist.instrument.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.instrument.entity.Instrument;

public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {
  Optional<Instrument> findBySymbolIgnoreCase(String symbol);

  @Query("SELECT i FROM Instrument i WHERE UPPER(i.symbol) IN :symbols")
  List<Instrument> findBySymbolInIgnoreCase(@Param("symbols") Collection<String> symbols);

  @Query(
      "SELECT i FROM Instrument i WHERE "
          + "(:type IS NULL OR i.type = :type) AND ("
          + "LOWER(i.symbol) LIKE LOWER(CONCAT('%', :query, '%')) OR "
          + "LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))"
          + ") ORDER BY CASE WHEN LOWER(i.symbol) LIKE LOWER(CONCAT(:query, '%')) THEN 0 "
          + "WHEN LOWER(i.name) LIKE LOWER(CONCAT(:query, '%')) THEN 1 ELSE 2 END, i.symbol ASC")
  List<Instrument> searchInstruments(@Param("type") InstrumentType type, @Param("query") String query);

  @Query(
      "SELECT i FROM Instrument i WHERE "
          + "(:type IS NULL OR i.type = :type) AND ("
          + "LOWER(i.symbol) LIKE LOWER(CONCAT('%', :query, '%')) OR "
          + "LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))"
          + ") ORDER BY CASE WHEN LOWER(i.symbol) LIKE LOWER(CONCAT(:query, '%')) THEN 0 "
          + "WHEN LOWER(i.name) LIKE LOWER(CONCAT(:query, '%')) THEN 1 ELSE 2 END, i.symbol ASC")
  Page<Instrument> searchInstruments(@Param("type") InstrumentType type, @Param("query") String query, Pageable pageable);
}
