package ch.alpenflight.clubs.domain;

import java.util.List;

public interface MemberStateRepository {

    List<MemberState> findAll();
}
