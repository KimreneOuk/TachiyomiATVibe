"""Pure-logic tests for build_inpaint_clusters (no cv2/scipy/onnx)."""

from server import build_inpaint_clusters


def _item(bbox, parent_label, parent=None):
    if parent is None:
        parent = list(bbox)
    return {
        "bbox": list(bbox),
        "parent": list(parent),
        "parent_label": parent_label,
    }


def test_two_bubble_same_parent():
    items = [
        _item([10, 10, 50, 50], 0, parent=[5, 5, 55, 55]),
        _item([20, 30, 40, 60], 1, parent=[5, 5, 55, 55]),
    ]
    clusters = build_inpaint_clusters(items, 200, 200)
    assert len(clusters) == 1
    c = clusters[0]
    assert len(c["boxes"]) == 2
    assert c["parent_rect"] == [5, 5, 55, 55]
    assert c["is_free"] == [False, False]


def test_two_bubble_different_parents():
    items = [
        _item([10, 10, 50, 50], 0, parent=[5, 5, 55, 55]),
        _item([100, 100, 140, 140], 1, parent=[95, 95, 145, 145]),
    ]
    clusters = build_inpaint_clusters(items, 200, 200)
    assert len(clusters) == 2
    for c in clusters:
        assert c["is_free"] == [False]
        assert c["parent_rect"] is not None


def test_free_close_together():
    items = [
        _item([10, 10, 30, 30], 2),
        _item([35, 10, 55, 30], 2),
    ]
    clusters = build_inpaint_clusters(items, 200, 200, cluster_distance=100)
    assert len(clusters) == 1
    c = clusters[0]
    assert len(c["boxes"]) == 2
    assert c["parent_rect"] is None
    assert c["is_free"] == [True, True]


def test_free_far_apart():
    items = [
        _item([10, 10, 30, 30], 2),
        _item([150, 150, 170, 170], 2),
    ]
    clusters = build_inpaint_clusters(items, 200, 200, cluster_distance=50)
    assert len(clusters) == 2
    for c in clusters:
        assert c["parent_rect"] is None
        assert c["is_free"] == [True]


def test_mixed_bubble_and_free():
    items = [
        _item([10, 10, 50, 50], 0, parent=[5, 5, 55, 55]),
        _item([100, 10, 120, 30], 2),
        _item([125, 10, 145, 30], 2),
    ]
    clusters = build_inpaint_clusters(items, 200, 200, cluster_distance=100)
    # Bubble cluster + free cluster
    assert len(clusters) == 2
    bubble_clusters = [c for c in clusters if c["parent_rect"] is not None]
    free_clusters = [c for c in clusters if c["parent_rect"] is None]
    assert len(bubble_clusters) == 1
    assert len(free_clusters) == 1
    assert bubble_clusters[0]["is_free"] == [False]
    assert free_clusters[0]["is_free"] == [True, True]


def test_boxes_clamped_to_page():
    items = [
        _item([-10, -10, 10, 10], 2),
        _item([15, 5, 30, 25], 2),
    ]
    clusters = build_inpaint_clusters(items, 200, 200, cluster_distance=300)
    assert len(clusters) == 1
    c = clusters[0]
    for box in c["boxes"]:
        x1, y1, x2, y2 = box
        assert 0 <= x1 <= x2 <= 200
        assert 0 <= y1 <= y2 <= 200
    assert c["boxes"][0][0] == 0
    assert c["boxes"][0][1] == 0
