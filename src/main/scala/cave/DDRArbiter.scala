/*
 *    __   __     __  __     __         __
 *   /\ "-.\ \   /\ \/\ \   /\ \       /\ \
 *   \ \ \-.  \  \ \ \_\ \  \ \ \____  \ \ \____
 *    \ \_\\"\_\  \ \_____\  \ \_____\  \ \_____\
 *     \/_/ \/_/   \/_____/   \/_____/   \/_____/
 *    ______     ______       __     ______     ______     ______
 *   /\  __ \   /\  == \     /\ \   /\  ___\   /\  ___\   /\__  _\
 *   \ \ \/\ \  \ \  __<    _\_\ \  \ \  __\   \ \ \____  \/_/\ \/
 *    \ \_____\  \ \_____\ /\_____\  \ \_____\  \ \_____\    \ \_\
 *     \/_____/   \/_____/ \/_____/   \/_____/   \/_____/     \/_/
 *
 *  https://joshbassett.info
 *  https://twitter.com/nullobject
 *  https://github.com/nullobject
 *
 *  Copyright (c) 2020 Josh Bassett
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package cave

import axon.mem._
import axon.types._
import cave.types._
import chisel3._
import chisel3.util._
import axon.util.Counter

/**
 * A DDR memory arbiter.
 *
 * The DDR memory arbiter routes requests from multiple input ports to a single output port.
 */
class DDRArbiter extends Module {
  val io = IO(new Bundle {
    /** DDR port */
    val ddr = new BurstReadWriteMemIO(DDRArbiter.ADDR_WIDTH, DDRArbiter.DATA_WIDTH)
    /** Cache port */
    val cache = Flipped(new CacheIO)
    /** Tile ROM port */
    val tileRom = Flipped(new TileRomIO)
    /** Frame buffer to DDR port */
    val fbToDDR = Flipped(BurstWriteMemIO(DDRArbiter.ADDR_WIDTH, DDRArbiter.DATA_WIDTH))
    /** Frame buffer from DDR port */
    val fbFromDDR = Flipped(BurstReadMemIO(DDRArbiter.ADDR_WIDTH, DDRArbiter.DATA_WIDTH))
    /** Download port */
    val download = DownloadIO()
    /** Debug port */
    val debug = new Bundle {
      val idle = Output(Bool())
      val check1 = Output(Bool())
      val check2 = Output(Bool())
      val check3 = Output(Bool())
      val check4 = Output(Bool())
      val cacheReq = Output(Bool())
      val gfxReq = Output(Bool())
      val cacheWait = Output(Bool())
      val gfxWait = Output(Bool())
      val fbToDDR = Output(Bool())
      val fbFromDDR = Output(Bool())
      val download = Output(Bool())
    }
  })

  // States
  object State {
    val idle :: check1 :: check2 :: check3 :: check4 :: cacheReq :: cacheWait :: gfxReq :: gfxWait :: fbFromDDR :: fbToDDR :: download :: Nil = Enum(12)
  }

  // Wires
  val nextState = Wire(UInt())

  // Registers
  val stateReg = RegNext(nextState, State.idle)
  val cacheReqReg = RegInit(false.B)
  val cacheAddrReg = RegInit(0.U)
  val cacheDataReg = Reg(Vec(DDRArbiter.CACHE_BURST_LENGTH, Bits(DDRArbiter.DATA_WIDTH.W)))
  val gfxReqReg = RegInit(false.B)
  val gfxAddrReg = RegInit(0.U)
  val gfxTinyBurstReg = RegInit(false.B)

  // Set download data and mask
  val downloadData = Cat(Seq.tabulate(8) { _ => io.download.dout })
  val downloadMask = 1.U << io.download.addr(2, 0)

  // Set the graphics burst length
  val gfxBurstLength = Mux(gfxTinyBurstReg, 8.U, 16.U)

  // Counters
  val (rrCounterValue, _) = Counter.static(4, enable = stateReg === State.idle && nextState =/= State.idle)
  val (cacheBurstValue, cacheBurstDone) = Counter.static(DDRArbiter.CACHE_BURST_LENGTH,
    enable = stateReg === State.cacheWait && io.ddr.valid,
    reset = stateReg === State.cacheReq
  )
  val (gfxBurstValue, gfxBurstDone) = Counter.dynamic(gfxBurstLength,
    enable = stateReg === State.gfxWait && io.ddr.valid,
    reset = stateReg === State.gfxReq
  )
  val (fbFromDDRBurstValue, fbFromDDRBurstDone) = Counter.dynamic(io.fbFromDDR.burstCount,
    enable = stateReg === State.fbFromDDR && io.ddr.valid,
    reset = stateReg =/= State.fbFromDDR
  )
  val (fbToDDRBurstValue, fbToDDRBurstDone) = Counter.dynamic(io.fbToDDR.burstCount,
    enable = stateReg === State.fbToDDR && io.fbToDDR.wr && !io.ddr.waitReq,
    reset = stateReg =/= State.fbToDDR
  )

  // Shift the DDR output data into the data register
  when(io.ddr.valid) { cacheDataReg := cacheDataReg.tail :+ io.ddr.dout }

  // Latch cache requests
  when(io.cache.rd) {
    cacheReqReg := true.B
    cacheAddrReg := io.cache.addr
  }.elsewhen(cacheBurstDone) {
    cacheReqReg := false.B
  }

  // Latch graphics requests
  when(io.tileRom.rd) {
    gfxReqReg := true.B
    gfxAddrReg := io.tileRom.addr
    gfxTinyBurstReg := io.tileRom.tinyBurst
  }.elsewhen(gfxBurstDone) {
    gfxReqReg := false.B
  }

  // Default to the previous state
  nextState := stateReg

  // FSM
  switch(stateReg) {
    is(State.idle) {
      nextState := MuxLookup(rrCounterValue, State.check4, Seq(
        0.U -> State.check1,
        1.U -> State.check2,
        2.U -> State.check3
      ))
    }

    is(State.check1) {
      nextState := MuxCase(stateReg, Seq(
        io.fbFromDDR.rd -> State.fbFromDDR,
        io.download.enable -> State.download,
        cacheReqReg -> State.cacheReq,
        gfxReqReg -> State.gfxReq,
        io.fbToDDR.wr -> State.fbToDDR
      ))
    }

    is(State.check2) {
      nextState := MuxCase(stateReg, Seq(
        io.fbFromDDR.rd -> State.fbFromDDR,
        io.download.enable -> State.download,
        gfxReqReg -> State.gfxReq,
        io.fbToDDR.wr -> State.fbToDDR,
        cacheReqReg -> State.cacheReq
      ))
    }

    is(State.check3) {
      nextState := MuxCase(stateReg, Seq(
        io.fbFromDDR.rd -> State.fbFromDDR,
        io.download.enable -> State.download,
        io.fbToDDR.wr -> State.fbToDDR,
        gfxReqReg -> State.gfxReq,
        cacheReqReg -> State.cacheReq
      ))
    }

    is(State.check4) {
      nextState := MuxCase(stateReg, Seq(
        io.fbFromDDR.rd -> State.fbFromDDR,
        io.download.enable -> State.download,
        gfxReqReg -> State.gfxReq,
        cacheReqReg -> State.cacheReq,
        io.fbToDDR.wr -> State.fbToDDR
      ))
    }

    is(State.cacheReq) {
      when(!io.ddr.waitReq) { nextState := State.cacheWait }
    }

    is(State.cacheWait) {
      when(cacheBurstDone) { nextState := State.idle }
    }

    is(State.gfxReq) {
      when(!io.ddr.waitReq) { nextState := State.gfxWait }
    }

    is(State.gfxWait) {
      when(gfxBurstDone) { nextState := State.idle }
    }

    is(State.fbFromDDR) {
      when(fbFromDDRBurstDone) { nextState := State.idle }
    }

    is(State.fbToDDR) {
      when(fbToDDRBurstDone) { nextState := State.idle }
    }

    is(State.download) {
      when(!io.download.enable) { nextState := State.idle }
    }
  }

  // Outputs
  io.ddr.rd := MuxLookup(stateReg, false.B, Seq(
    State.cacheReq -> true.B,
    State.gfxReq -> true.B,
    State.fbFromDDR -> io.fbFromDDR.rd
  ))
  io.ddr.wr := MuxLookup(stateReg, false.B, Seq(
    State.fbToDDR -> io.fbToDDR.wr,
    State.download -> io.download.wr
  ))
  io.ddr.addr := MuxLookup(stateReg, 0.U, Seq(
    State.cacheReq -> cacheAddrReg,
    State.gfxReq -> gfxAddrReg,
    State.fbFromDDR -> io.fbFromDDR.addr,
    State.fbToDDR -> io.fbToDDR.addr,
    State.download -> io.download.addr
  ))
  io.ddr.burstCount := MuxLookup(stateReg, 1.U, Seq(
    State.cacheReq -> DDRArbiter.CACHE_BURST_LENGTH.U,
    State.gfxReq -> gfxBurstLength,
    State.fbFromDDR -> io.fbFromDDR.burstCount,
    State.fbToDDR -> io.fbToDDR.burstCount
  ))
  io.ddr.mask := Mux(stateReg === State.download, downloadMask, 0xff.U)
  io.ddr.din := MuxLookup(stateReg, 0.U, Seq(
    State.fbToDDR -> io.fbToDDR.din,
    State.download -> downloadData
  ))
  io.cache.valid := RegNext(cacheBurstDone, false.B)
  io.cache.dout := cacheDataReg.asUInt
  io.tileRom.burstDone := RegNext(gfxBurstDone, false.B)
  io.tileRom.valid := Mux(stateReg === State.gfxWait, io.ddr.valid, false.B)
  io.tileRom.dout := io.ddr.dout
  io.fbFromDDR.waitReq := Mux(stateReg === State.fbFromDDR, io.ddr.waitReq, true.B)
  io.fbFromDDR.valid := Mux(stateReg === State.fbFromDDR, io.ddr.valid, false.B)
  io.fbFromDDR.dout := io.ddr.dout
  io.fbToDDR.waitReq := Mux(stateReg === State.fbToDDR, io.ddr.waitReq, true.B)
  io.download.waitReq := Mux(stateReg === State.download, io.download.wr && io.ddr.waitReq, io.download.wr)
  io.debug.idle := stateReg === State.idle
  io.debug.check1 := stateReg === State.check1
  io.debug.check2 := stateReg === State.check2
  io.debug.check3 := stateReg === State.check3
  io.debug.check4 := stateReg === State.check4
  io.debug.cacheReq := stateReg === State.cacheReq
  io.debug.gfxReq := stateReg === State.gfxReq
  io.debug.cacheWait := stateReg === State.cacheWait
  io.debug.gfxWait := stateReg === State.gfxWait
  io.debug.fbFromDDR := stateReg === State.fbFromDDR
  io.debug.fbToDDR := stateReg === State.fbToDDR
  io.debug.download := stateReg === State.download

  printf(p"DDRArbiter(state: $stateReg, nextState: $nextState, cache: $cacheBurstValue ($cacheBurstDone), cacheValid: ${io.cache.valid}, gfx: $gfxBurstValue ($gfxBurstDone), gfxValid: ${io.tileRom.valid}, fbFromDDR: $fbFromDDRBurstValue ($fbFromDDRBurstDone), fbFromDDRValid: ${io.fbFromDDR.valid}, fbToDDR: $fbToDDRBurstValue ($fbToDDRBurstDone), fbToDDRWaitReq: ${io.fbToDDR.waitReq})\n")
}

object DDRArbiter {
  /** The width of the address bus */
  val ADDR_WIDTH = 32
  /** The width of the data bus */
  val DATA_WIDTH = 64
  /** The length of the cache burst */
  val CACHE_BURST_LENGTH = 4
}
