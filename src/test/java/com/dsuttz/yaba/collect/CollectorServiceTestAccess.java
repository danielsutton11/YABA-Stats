package com.dsuttz.yaba.collect;

import com.dsuttz.yaba.store.DataRepository;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class CollectorServiceTestAccess {
    public static Long[] extract(JsonNode j) { return CollectorService.extractStats(j); }
    public static List<DataRepository.RosterMember> members(JsonNode j) { return CollectorService.parseMembers(j); }
}
