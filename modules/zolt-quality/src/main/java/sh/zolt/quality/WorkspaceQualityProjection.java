package sh.zolt.quality;

import java.util.Map;

record WorkspaceQualityProjection(Map<String, WorkspaceMemberQualityView> members) {
    WorkspaceQualityProjection {
        members = Map.copyOf(members);
    }

    WorkspaceMemberQualityView member(String path) {
        WorkspaceMemberQualityView view = members.get(path);
        if (view == null) {
            throw new IllegalArgumentException(
                    "No quality projection exists for workspace member `" + path + "`.");
        }
        return view;
    }
}
