import kotlin.math.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.random.Random.Default.nextInt


data class Coord(val x: Int, val y: Int) {

    constructor(input: Scanner) : this(input.nextInt(), input.nextInt())

    operator fun plus(other: Coord): Coord {
        return Coord(x + other.x, y + other.y)
    }

    operator fun minus(other: Coord): Coord {
        return Coord(x - other.x, y - other.y)
    }

    // Manhattan distance (for 4 directions maps)
    // see: https://en.wikipedia.org/wiki/Taxicab_geometry
    fun distance(other: Coord): Int {
        return abs(x - other.x) + abs(y - other.y)
    }

    override fun toString(): String {
        return "$x $y"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Coord

        if (x != other.x) return false
        if (y != other.y) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        return result
    }


}


class Cell {
    val ore: Int?
    val hole: Boolean
    var trap: Boolean
    val coord: Coord

    constructor(ore: Int?, hole: Boolean, trap: Boolean, coord: Coord) {
        this.ore = ore
        this.hole = hole
        this.trap = trap
        this.coord = coord
    }

    constructor(input: Scanner) : this(input.next().toIntOrNull(), input.next() != "0", false, Coord(0, 0))

    override fun toString(): String {
        return "ore_$ore hole_$hole trap_$trap coord_$coord"
    }
}


sealed class Action {

    object None : Action() {
        override fun toString(): String = "WAIT"
    }

    class Move(val pos: Coord) : Action() {
        override fun toString(): String = "MOVE $pos"
    }

    class Dig(val pos: Coord) : Action() {
        override fun toString(): String = "DIG $pos"
    }

    class Request(val item: EntityType) : Action() {
        override fun toString(): String = "REQUEST $item"
    }

}


enum class EntityType {
    NOTHING, ALLY_ROBOT, ENEMY_ROBOT, RADAR, TRAP, AMADEUSIUM;


    companion object {
        fun valueOf(id: Int): EntityType {
            return values()[id + 1]
        }
    }
}

class Entity(// Updated every turn
    val id: Int,
    val type: EntityType, val pos: Coord, val item: EntityType) {

    constructor(input: Scanner) : this(
        id = input.nextInt(),
        type = EntityType.valueOf(input.nextInt()),
        pos = Coord(input),
        item = EntityType.valueOf(input.nextInt())
    )

    // Computed for my robots
    var action: Action = Action.None
    var message: String? = null
    var start = Coord(0,0)
    var dest = Coord(0, 0)
    var idle = true

    val isAlive: Boolean
        get() = DEAD_POS != pos

    fun printAction() {
        if (message == null) println(action)
        else println("$action $message")
    }

    fun requestRadarOrTrap(board: Board): Entity {
        if (this.item == EntityType.NOTHING && this.pos.x == 0 && board.myRadarCooldown == 0 && !board.radarRequested) {
            //System.err.println("rob_${this.id} requested radar")
            this.action = Action.Request(EntityType.RADAR)
            board.radarRequested = true
            this.idle = false
        } else if (this.item == EntityType.NOTHING && this.pos.x == 0 && board.myTrapCooldown == 0 && !board.trapRequested) {
            //System.err.println("rob_${this.id} requested trap")
            this.action = Action.Request(EntityType.TRAP)
            board.trapRequested = true
            this.idle = false
        }
        return this
    }

    fun setRadar(board: Board): Entity {
        if (this.item == EntityType.RADAR && this.pos.x != 0) {
            //System.err.println("rob_${this.id} setting radar")
            when {
                board.myRadarPos.isEmpty() -> this.action = safeDig(board, this, Coord(6, 9)) //first radar
                board.myRadarPos.size == 1 -> this.action = safeDig(board, this, Coord(8, 4))
                board.myRadarPos.size == 2 -> this.action = safeDig(board, this, Coord(12, 10))
                board.myRadarPos.size == 3 -> this.action = safeDig(board, this, Coord(17, 4))
                board.myRadarPos.size == 4 -> this.action = safeDig(board, this, Coord(20, 10))
                board.myRadarPos.size == 5 -> this.action = safeDig(board, this, Coord(23, 4))
                board.myRadarPos.size == 6 -> this.action = safeDig(board, this, Coord(26, 10))
                board.cellsOre.isNotEmpty() -> {
                    //System.err.println("cellsWithOres.isNotEmpty(): ${board.cellsOre}")
                    digNearestCellWithOre(board, this)
                }
                else -> {
                    var newRadarPos = getNewRadarPosition(board)
                    this.action = digIfNotBusy(board, newRadarPos, this)
                }
            }
        }
        return this
    }

    fun mineOreAndSetTrap(board: Board): Entity {
        if ((this.item == EntityType.NOTHING || this.item == EntityType.TRAP) && this.pos.x != 0) {
            //System.err.println("rob_${this.id} mineOreAndSetTrap")
            if (board.cellsOre.isNotEmpty()) {
                digNearestCellWithOre(board, this)
            } else if (!board.getCell(this.pos)!!.hole) {
                this.action = safeDig(board, this, this.pos)
            } else this.action = safeDig(board, this, getNewDigPosition(board, this))
        }
        return this
    }

    fun deliverOreToBase(): Entity {
        if (this.item == EntityType.AMADEUSIUM) {
            //System.err.println("rob_${this.id} deliverOreToBase")
            this.idle = false
            this.action = Action.Move(Coord(0, this.pos.y))
        }
        return this
    }

    fun moveIfIdle(board: Board) {
        if (this.idle) {
            //System.err.println("rob_${this.id} moveIfIdle")
            this.action = safeDig(board, this, getNewDigPosition(board, this))
        }
    }

    private fun digNearestCellWithOre(board: Board, robot: Entity) {
        val nearestCell: Cell?
        if (board.cellsOre.isNotEmpty()) {
            //System.err.println("cellsWithOres.isNotEmpty(): ${board.cellsOre}")
            //System.err.println("rob_${this.id} digNearestCellWithOre")
            if (board.cellsOre.none { !it!!.hole }) {
                nearestCell = board.cellsOre.minBy { it!!.coord.distance(robot.pos) }
                //System.err.println("nearestCell with hole_${nearestCell}")
                robot.action = safeDig(board, robot, nearestCell!!.coord)
            } else {
                nearestCell = board.cellsOre.filter { !it!!.hole }.minBy { it!!.coord.distance(robot.pos) }
                //System.err.println("nearestCell_${nearestCell}")
                robot.action = safeDig(board, robot, nearestCell!!.coord)
            }
        }
    }

    private fun getNewRadarPosition (board: Board): Coord {
        val lastRadarPos = board.myRadarPos.last()
        val radarPosFromX = if (lastRadarPos.x >= board.width-8) 4 else lastRadarPos.x+4
        val radarPosFromY = if (lastRadarPos.y >= board.height-8) 3 else lastRadarPos.y+4
        var pos: Coord
        do {
            pos = Coord(nextInt(radarPosFromX, board.width-4), nextInt(radarPosFromY, board.height-4))
        } while (board.getCell(pos)!!.hole || board.getCell(pos)!!.trap)
        return pos
    }

    private fun getNewDigPosition (board: Board, robot: Entity): Coord {
        return if (board.holes.isNotEmpty()) {
            val lastDigPos = board.holes.minBy { it.distance(robot.pos) }
            val posFromX = if (lastDigPos!!.x >= board.width - 8) 4 else lastDigPos.x + 4
            val posFromY = if (lastDigPos.y >= board.height - 8) 3 else lastDigPos.y + 4
            var pos: Coord
            do {
                pos = Coord(nextInt(posFromX, board.width - 4), nextInt(posFromY, board.height - 4))
            } while (board.getCell(pos)!!.trap)
            return pos
        } else getRandomCoord(board)
    }

    private fun digIfNotBusy(board: Board, coord: Coord, robot: Entity): Action {
        return if (robot.start == robot.dest) {
            robot.start = robot.pos
            robot.dest = coord
            robot.idle = true
            safeDig(board, robot, robot.dest)
        } else {
            robot.start = robot.pos
            robot.idle = false
            safeDig(board, robot, robot.dest)
        }
    }

    private fun safeDig(board: Board, robot: Entity, dest: Coord): Action {
        return if (!board.getCell(dest)!!.trap) {
            robot.idle = false
            //System.err.println("rob_${robot.id} safe digging $dest")
            Action.Dig(dest)
        } else safeDig(board, robot, dest.plus(Coord(1,1)))
    }

    private fun getRandomCoord(board: Board): Coord {
        val randomX = nextInt(0, board.width)
        val randomY = nextInt(0, board.height)
        return Coord(randomX, randomY)
    }

    companion object {
        private val DEAD_POS = Coord(-1, -1)
    }
}


class Team {
    var score: Int = 0
    val robots: MutableCollection<Entity> = mutableListOf()

    fun readScore(input: Scanner) {
        score = input.nextInt()
        robots.clear()
    }
}


class Board(// Given at startup
    val width: Int, val height: Int,// Updated each turn
    val myTeam: Team, val opponentTeam: Team) {

    constructor(input: Scanner) : this(
        width = input.nextInt(),
        height = input.nextInt(),
        myTeam = Team(),
        opponentTeam = Team()
    )

    private val cells: Array<Array<Cell?>> = Array(height) { arrayOfNulls<Cell>(width) }
    var cellsOre: ArrayList<Cell?> = ArrayList()
    var holes: ArrayList<Coord> = ArrayList()
    var myRadarCooldown: Int = 0
    var myTrapCooldown: Int = 0
    val entitiesById: MutableMap<Int, Entity> = mutableMapOf()
    val myRadarPos: MutableCollection<Coord> = mutableSetOf()
    val myTrapPos: MutableCollection<Coord> = mutableSetOf()
    var trapRequested = false
    var radarRequested = false
    var turnCounter = 0

    fun update(input: Scanner) {
        // Read new data
        myTeam.readScore(input)
        opponentTeam.readScore(input)
        cellsOre.clear()
        holes.clear()

        when {
            turnCounter >= 5 -> {
                turnCounter = 0
                trapRequested = false
                radarRequested = false
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                cells[y][x] = Cell(input)
                if (cells[y][x]!!.ore != null && cells[y][x]!!.ore != 0) {
                    //&& !cells[y][x]!!.hole
                    var cell = Cell(cells[y][x]!!.ore, cells[y][x]!!.hole, false, Coord(x, y))
                    cellsOre.add(cell)
                }
                if (cells[y][x]!!.hole) holes.add(Coord(x, y))
            }
        }

        val entityCount = input.nextInt()
        myRadarCooldown = input.nextInt()
        myTrapCooldown = input.nextInt()
        myRadarPos.clear()
        myTrapPos.clear()

        repeat(entityCount) {
            val entity = Entity(input)
            entitiesById[entity.id] = entity
            when {
                entity.type == EntityType.ALLY_ROBOT -> myTeam.robots.add(entity)
                entity.type == EntityType.ENEMY_ROBOT -> opponentTeam.robots.add(entity)
                entity.type == EntityType.RADAR -> myRadarPos.add(entity.pos)
                entity.type == EntityType.TRAP -> {
                    myTrapPos.add(entity.pos)
                    cells[entity.pos.y][entity.pos.x]!!.trap = true
                }
            }
        }
        turnCounter++
    }

    fun cellExist(pos: Coord): Boolean {
        return pos.x in 0 until width &&
                pos.y in 0 until height
    }

    fun getCell(pos: Coord): Cell? {
        return if (cellExist(pos)) {
            cells[pos.y][pos.x]
        } else cells.last().last()
    }

}


class Player {

    private val input = Scanner(System.`in`)

    fun run() {
        // Parse initial conditions
        val board = Board(input)

        while (true) {
            // Parse current state of the game
            board.update(input)

            // Insert your strategy here
            for (robot in board.myTeam.robots) {
                if (!robot.isAlive) continue

                robot.requestRadarOrTrap(board)
                    .setRadar(board)
                    .mineOreAndSetTrap(board)
                    .deliverOreToBase()
                    .moveIfIdle(board)
            }

            // Send your actions for this turn
            for (robot in board.myTeam.robots) {
                robot.printAction()
            }
        }
    }
}

fun main() {
    Player().run()
}