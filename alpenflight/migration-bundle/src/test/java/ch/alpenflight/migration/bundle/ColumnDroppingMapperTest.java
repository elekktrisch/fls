package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnDroppingMapperTest {

    private static FakeMapper threeColumnMapper() {
        return new FakeMapper(
                EntityType.COUNTRY,
                new String[] {"legacy_guid", "iso2_code", "name"},
                List.of(EntityType.CLUB));
    }

    @Test
    void columnsOmitTheDroppedColumnInOrder() {
        ColumnDroppingMapper decorated =
                new ColumnDroppingMapper(threeColumnMapper(), "iso2_code");
        assertThat(decorated.wireColumns()).containsExactly("legacy_guid", "name");
    }

    @Test
    void droppingAColumnTheDelegateDoesNotDeclareFailsFast() {
        assertThatThrownBy(() -> new ColumnDroppingMapper(threeColumnMapper(), "no_such_column"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_such_column");
    }

    @Test
    void entityTypeAndForeignKeysDelegateUnchanged() {
        ColumnDroppingMapper decorated =
                new ColumnDroppingMapper(threeColumnMapper(), "name");
        assertThat(decorated.entityType()).isEqualTo(EntityType.COUNTRY);
        assertThat(decorated.foreignKeyTargets()).containsExactly(EntityType.CLUB);
    }

    @Test
    void writeAndReadDelegateToTheWrappedMapper() throws Exception {
        FakeMapper delegate = threeColumnMapper();
        ColumnDroppingMapper decorated = new ColumnDroppingMapper(delegate, "name");
        decorated.writeNdjson(null, null);
        decorated.readEntity(null, null);
        assertThat(delegate.writeCalls).isEqualTo(1);
        assertThat(delegate.readCalls).isEqualTo(1);
    }
}
