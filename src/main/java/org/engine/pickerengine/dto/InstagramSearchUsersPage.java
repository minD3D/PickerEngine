package org.engine.pickerengine.dto;

import java.util.List;

public record InstagramSearchUsersPage(
        String query,
        List<InstagramSearchUser> users,
        String nextMaxId,
        String searchSessionId,
        String rankToken,
        boolean hasMore
) {
}
