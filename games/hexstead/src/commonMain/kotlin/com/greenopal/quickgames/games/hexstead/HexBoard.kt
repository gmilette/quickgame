package com.greenopal.quickgames.games.hexstead

/**
 * The fixed shape of the board: a centre hex (index 0) and a ring of six (1..6, clockwise from
 * the top). Hexes are flat-topped. Positions are on an integer lattice so corners shared by
 * several hexes compare equal: x is in units of half a hex side, y in units of half a hex height.
 */
object HexBoard {
    data class Point(val x: Int, val y: Int)
    data class Edge(val a: Int, val b: Int)

    const val HEX_COUNT = 7
    const val CENTER = 0

    /** Lattice extent: corners span x in -5..5 and y in -3..3. */
    const val HALF_WIDTH = 5
    const val HALF_HEIGHT = 3

    private val axial = listOf(0 to 0, 0 to -1, 1 to -1, 1 to 0, 0 to 1, -1 to 1, -1 to 0)
    private val cornerOffsets = listOf(2 to 0, 1 to 1, -1 to 1, -2 to 0, -1 to -1, 1 to -1)

    val hexCenters: List<Point> = axial.map { (q, r) -> Point(3 * q, 2 * r + q) }

    val vertices: List<Point> = hexCenters
        .flatMap { c -> cornerOffsets.map { (dx, dy) -> Point(c.x + dx, c.y + dy) } }
        .distinct()
        .sortedWith(compareBy({ it.y }, { it.x }))

    /** Corner ids of each hex, in order around the hex. */
    val hexVertices: List<List<Int>> = hexCenters.map { c ->
        cornerOffsets.map { (dx, dy) -> vertices.indexOf(Point(c.x + dx, c.y + dy)) }
    }

    val edges: List<Edge> = hexVertices
        .flatMap { corners -> corners.indices.map { i -> corners[i] to corners[(i + 1) % corners.size] } }
        .map { (a, b) -> Edge(minOf(a, b), maxOf(a, b)) }
        .distinct()
        .sortedWith(compareBy({ it.a }, { it.b }))

    val vertexHexes: List<List<Int>> = vertices.indices.map { v ->
        hexVertices.indices.filter { v in hexVertices[it] }
    }

    val vertexEdges: List<List<Int>> = vertices.indices.map { v ->
        edges.indices.filter { edges[it].a == v || edges[it].b == v }
    }

    fun otherEnd(edge: Int, vertex: Int): Int = edges[edge].let { if (it.a == vertex) it.b else it.a }

    /** Ring hexes next to each other. Every ring hex also touches the centre. */
    fun ringNeighbors(hex: Int): List<Int> {
        if (hex == CENTER) return (1..6).toList()
        val before = if (hex == 1) 6 else hex - 1
        val after = if (hex == 6) 1 else hex + 1
        return listOf(before, after)
    }
}
