package cn.willgao.teleportbeacon;

import java.util.UUID;

/** 改名申请：非放置者想给别人的信标改名。 */
final class Request {
    final int id;
    final UUID point;
    final UUID requester;
    final String requesterName;
    final String newName;
    final long time;

    Request(int id, UUID point, UUID requester, String requesterName, String newName, long time) {
        this.id = id; this.point = point; this.requester = requester;
        this.requesterName = requesterName; this.newName = newName; this.time = time;
    }
}
