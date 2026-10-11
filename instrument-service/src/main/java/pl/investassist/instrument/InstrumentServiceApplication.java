package pl.investassist.instrument;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.enums.Currency;
import pl.investassist.instrument.enums.InstrumentType;
import pl.investassist.instrument.repository.InstrumentRepository;

@SpringBootApplication
public class InstrumentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(InstrumentServiceApplication.class, args);
  }

  @Bean
  CommandLineRunner initBonds(InstrumentRepository repository) {
    return args -> {
      if (repository.findBySymbolIgnoreCase("PL_BOND").isEmpty()) {
        Instrument bond = new Instrument();
        bond.setSymbol("PL_BOND");
        bond.setName("Polska obligacja skarbowa");
        bond.setType(InstrumentType.BOND);
        bond.setCurrency(Currency.PLN);
        repository.save(bond);
      }
    };
  }
}
