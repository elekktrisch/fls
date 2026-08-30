package ch.alpenflight.platform.id;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.UUID;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

public class UuidV7Generator implements BeforeExecutionGenerator {

    @Override
    public Object generate(
            SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
        Connection connection =
                session.getJdbcCoordinator().getLogicalConnection().getPhysicalConnection();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("select uuidv7()")) {
            resultSet.next();
            return resultSet.getObject(1, UUID.class);
        } catch (SQLException e) {
            throw new HibernateException("Could not generate a UUID v7 id via uuidv7()", e);
        }
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EventTypeSets.INSERT_ONLY;
    }
}
