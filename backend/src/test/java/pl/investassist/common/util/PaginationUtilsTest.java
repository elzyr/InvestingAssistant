package pl.investassist.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaginationUtilsTest {

  @Test
  void toBoundedPageable_appliesDefaultsAndEnforcesMaxSize() {
    var bounded = PaginationUtils.toBoundedPageable(null, null);
    assertThat(bounded.getPageNumber()).isEqualTo(0);
    assertThat(bounded.getPageSize()).isEqualTo(20);

    assertThatThrownBy(() -> PaginationUtils.toBoundedPageable(0, 51))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
