package pl.investassist.instrument.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.enums.InstrumentType;

public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {
  Optional<Instrument> findBySymbolIgnoreCase(String symbol);

  @Query(
      "SELECT i FROM Instrument i WHERE "
          + "(:type IS NULL OR i.type = :type) AND ("
          + "LOWER(i.symbol) LIKE LOWER(CONCAT('%', :query, '%')) OR "
          + "LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))"
          + ")")
  List<Instrument> searchInstruments(@Param("type") InstrumentType type, @Param("query") String query);
}
