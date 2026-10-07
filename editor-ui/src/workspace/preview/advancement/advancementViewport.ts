import type {PositionedAdvancement} from "./advancement.ts";

export interface AdvancementViewport {
    left: number;
    top: number;
    width: number;
    height: number;
}

export const advancementStepX = 56;
export const advancementStepY = 54;
export const advancementMargin = 32;

export function visibleAdvancements(nodes: PositionedAdvancement[], viewport: AdvancementViewport): PositionedAdvancement[] {
    return nodes.filter(node => {
        const x = advancementMargin + node.x * advancementStepX;
        const y = advancementMargin + node.y * advancementStepY;
        return intersects(x, y, x + 32, y + 32, viewport);
    });
}

export interface AdvancementConnection {
    x1: number;
    y1: number;
    x2: number;
    y2: number;
}

export function advancementConnections(nodes: PositionedAdvancement[], byId: Map<string, PositionedAdvancement>): AdvancementConnection[] {
    const groups = new Map<string, {x1: number; x2: number; middle: number; y: number; top: number; bottom: number}>();
    const segments: AdvancementConnection[] = [];
    for (const node of nodes) {
        const parent = node.visibleParent && byId.get(node.visibleParent);
        if (!parent) continue;
        const x1 = advancementMargin + parent.x * advancementStepX + 16;
        const y1 = advancementMargin + parent.y * advancementStepY + 16;
        const x2 = advancementMargin + node.x * advancementStepX + 16;
        const y2 = advancementMargin + node.y * advancementStepY + 16;
        const middle = (x1 + x2) / 2;
        const key = `${parent.id}:${x2}`;
        const group = groups.get(key) ?? {x1, x2, middle, y: y1, top: y1, bottom: y1};
        group.top = Math.min(group.top, y2);
        group.bottom = Math.max(group.bottom, y2);
        groups.set(key, group);
        segments.push({x1: middle, y1: y2, x2, y2});
    }
    for (const group of groups.values()) {
        segments.push({x1: group.x1, y1: group.y, x2: group.middle, y2: group.y});
        segments.push({x1: group.middle, y1: group.top, x2: group.middle, y2: group.bottom});
    }
    return segments;
}

export function visibleConnectionPath(segments: AdvancementConnection[], viewport: AdvancementViewport): string {
    const path: string[] = [];
    for (const line of segments) {
        if (intersects(Math.min(line.x1, line.x2), Math.min(line.y1, line.y2),
            Math.max(line.x1, line.x2), Math.max(line.y1, line.y2), viewport)) {
            path.push(`M${line.x1},${line.y1}L${line.x2},${line.y2}`);
        }
    }
    return path.join(" ");
}

function intersects(left: number, top: number, right: number, bottom: number, viewport: AdvancementViewport): boolean {
    const overscan = 64;
    return right >= viewport.left - overscan && left <= viewport.left + viewport.width + overscan
        && bottom >= viewport.top - overscan && top <= viewport.top + viewport.height + overscan;
}
