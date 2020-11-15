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

import axon.Util
import axon.cpu.m68k._
import axon.gpu._
import axon.mem._
import cave.gpu._
import cave.types._
import chisel3._
import chisel3.util._

/** Represents the CAVE arcade hardware. */
class CaveTop extends Module {
  val io = IO(new Bundle {
    /** CPU clock domain */
    val cpuClock = Input(Clock())
    /** CPU reset */
    val cpuReset = Input(Reset())
    /** Player port */
    val player = new PlayerIO
    /** Program ROM port */
    val progRom = new ProgRomIO
    /** Tile ROM port */
    val tileRom = new TileRomIO
    /** Frame buffer port */
    val frameBuffer = new FrameBufferIO
    /** Video port */
    val video = Input(new VideoIO)
    /** Debug port */
    val debug = new Bundle {
      val pc = Output(UInt())
      val pcw = Output(Bool())
    }
  })

  class CaveBlackBox extends BlackBox {
    val io = IO(new Bundle {
      val rst_i = Input(Reset())
      val clk_i = Input(Clock())
      val rst_68k_i = Input(Reset())
      val clk_68k_i = Input(Clock())
      val vblank_i = Input(Bool())
      val cpu = Flipped(new CPUIO)
      val memBus = new Bundle {
        val ack = Output(Bool())
        val data = Output(Bits(CPU.DATA_WIDTH.W))
      }
    })

    override def desiredName = "cave"
  }

  // Wires
  val startFrame = WireInit(false.B)

  // M68000 CPU
  val cpu = withClockAndReset(io.cpuClock, io.cpuReset) { Module(new CPU) }

  // Main RAM
  val mainRam = withClockAndReset(io.cpuClock, io.cpuReset) {
    Module(new SinglePortRam(
      addrWidth = Config.MAIN_RAM_ADDR_WIDTH,
      dataWidth = Config.MAIN_RAM_DATA_WIDTH
    ))
  }

  // Sprite RAM
  val spriteRam = withClockAndReset(io.cpuClock, io.cpuReset) {
    Module(new TrueDualPortRam(
      addrWidthA = Config.SPRITE_RAM_ADDR_WIDTH,
      dataWidthA = Config.SPRITE_RAM_DATA_WIDTH,
      addrWidthB = Config.SPRITE_RAM_GPU_ADDR_WIDTH,
      dataWidthB = Config.SPRITE_RAM_GPU_DATA_WIDTH
    ))
  }
  spriteRam.io.clockB := clock

  // Layer 0 RAM
  val layer0Ram = withClockAndReset(io.cpuClock, io.cpuReset) {
    Module(new TrueDualPortRam(
      addrWidthA = Config.LAYER_0_RAM_ADDR_WIDTH,
      dataWidthA = Config.LAYER_0_RAM_DATA_WIDTH,
      addrWidthB = Config.LAYER_0_RAM_GPU_ADDR_WIDTH,
      dataWidthB = Config.LAYER_0_RAM_GPU_DATA_WIDTH
    ))
  }
  layer0Ram.io.clockB := clock

  // Layer 1 RAM
  val layer1Ram = withClockAndReset(io.cpuClock, io.cpuReset) {
    Module(new TrueDualPortRam(
      addrWidthA = Config.LAYER_1_RAM_ADDR_WIDTH,
      dataWidthA = Config.LAYER_1_RAM_DATA_WIDTH,
      addrWidthB = Config.LAYER_1_RAM_GPU_ADDR_WIDTH,
      dataWidthB = Config.LAYER_1_RAM_GPU_DATA_WIDTH
    ))
  }
  layer1Ram.io.clockB := clock

  // Layer 2 RAM
  //
  // The layer 2 RAM masks address bits 14 and 15 on the CPU-side (i.e. the RAM is 8KB mirrored to 64KB).
  //
  // https://github.com/mamedev/mame/blob/master/src/mame/drivers/cave.cpp#L495
  val layer2Ram = withClockAndReset(io.cpuClock, io.cpuReset) {
    Module(new TrueDualPortRam(
      addrWidthA = Config.LAYER_2_RAM_ADDR_WIDTH,
      dataWidthA = Config.LAYER_2_RAM_DATA_WIDTH,
      addrWidthB = Config.LAYER_2_RAM_GPU_ADDR_WIDTH,
      dataWidthB = Config.LAYER_2_RAM_GPU_DATA_WIDTH
    ))
  }
  layer2Ram.io.clockB := clock

  // Palette RAM
  val paletteRam = withClockAndReset(io.cpuClock, io.cpuReset) {
    Module(new TrueDualPortRam(
      addrWidthA = Config.PALETTE_RAM_ADDR_WIDTH,
      dataWidthA = Config.PALETTE_RAM_DATA_WIDTH,
      addrWidthB = Config.PALETTE_RAM_GPU_ADDR_WIDTH,
      dataWidthB = Config.PALETTE_RAM_GPU_DATA_WIDTH
    ))
  }
  paletteRam.io.clockB := clock

  // Layer registers
  val layer0Info = withClockAndReset(io.cpuClock, io.cpuReset) { Module(new RegisterFile(Config.LAYER_INFO_NUM_REGS)) }
  val layer1Info = withClockAndReset(io.cpuClock, io.cpuReset) { Module(new RegisterFile(Config.LAYER_INFO_NUM_REGS)) }
  val layer2Info = withClockAndReset(io.cpuClock, io.cpuReset) { Module(new RegisterFile(Config.LAYER_INFO_NUM_REGS)) }

  // Video registers
  val videoRegs = withClockAndReset(io.cpuClock, io.cpuReset) { Module(new RegisterFile(8)) }

  // Sound registers
  val soundRegs = withClockAndReset(io.cpuClock, io.cpuReset) { Module(new RegisterFile(4)) }

  // TODO: Register this output
  val bufferSelect = videoRegs.io.regs(4)(0)

  // GPU
  val gpu = Module(new GPU)
  gpu.io.generateFrame := Util.rising(ShiftRegister(startFrame, 2))
  gpu.io.bufferSelect := bufferSelect
  gpu.io.tileRom <> io.tileRom
  gpu.io.spriteRam <> spriteRam.io.portB
  gpu.io.layer0Ram <> layer0Ram.io.portB
  gpu.io.layer1Ram <> layer1Ram.io.portB
  gpu.io.layer2Ram <> layer2Ram.io.portB
  gpu.io.layer0Info := RegNext(layer0Info.io.regs.asUInt)
  gpu.io.layer1Info := RegNext(layer1Info.io.regs.asUInt)
  gpu.io.layer2Info := RegNext(layer2Info.io.regs.asUInt)
  gpu.io.paletteRam <> paletteRam.io.portB
  gpu.io.frameBuffer <> io.frameBuffer

  // Cave
  val cave = Module(new CaveBlackBox)
  cave.io.rst_i := reset
  cave.io.clk_i := clock
  cave.io.clk_68k_i := io.cpuClock
  cave.io.rst_68k_i := io.cpuReset
  cave.io.vblank_i := io.video.vBlank
  cave.io.cpu <> cpu.io
  cpu.io.dtack := cave.io.memBus.ack
  cpu.io.din := cave.io.memBus.data

  // Memory map
  withClockAndReset(io.cpuClock, io.cpuReset) {
    // Program ROM
    cpu.memMap(0x000000 to 0x0fffff).romT(io.progRom) { _ + Config.PROG_ROM_OFFSET.U }
    // Main RAM
    cpu.memMap(0x100000 to 0x10ffff).ram(mainRam.io)
    // Sound regs
    cpu.memMap(0x300000 to 0x300003).wom(soundRegs.io.mem.asWriteMemIO)
    // Sprite RAM
    cpu.memMap(0x400000 to 0x40ffff).ram(spriteRam.io.portA)
    // Layer RAM
    cpu.memMap(0x500000 to 0x507fff).ram(layer0Ram.io.portA)
    cpu.memMap(0x600000 to 0x607fff).ram(layer1Ram.io.portA)
    cpu.memMap(0x700000 to 0x70ffff).ram(layer2Ram.io.portA)
    // IRQ cause
    cpu.memMap(0x800000 to 0x800007).r { (_, _) => 3.U }
    // Video regs
    cpu.memMap(0x800000 to 0x80007f).wom(videoRegs.io.mem.asWriteMemIO)
    // Trigger the start of a new frame
    cpu.memMap(0x800004).w { (_, _, data) => startFrame := data === 0x01f0.U }
    // Layer regs
    cpu.memMap(0x900000 to 0x900005).ram(layer0Info.io.mem)
    cpu.memMap(0xa00000 to 0xa00005).ram(layer1Info.io.mem)
    cpu.memMap(0xb00000 to 0xb00005).ram(layer2Info.io.mem)
    // Palette RAM
    cpu.memMap(0xc00000 to 0xc0ffff).ram(paletteRam.io.portA)
    // Player 1 inputs
    cpu.memMap(0xd00000 to 0xd00001).r { (_, _) => "b1111111".U ## ~io.player.player1 }
    // Player 2 inputs
    cpu.memMap(0xd00002 to 0xd00003).r { (_, _) => "b1111011".U ## ~io.player.player2 }
    // EEPROM
    cpu.memMap(0xe00000).w { (_, _, _) => /* TODO */ }
  }

  // Outputs
  io.tileRom.addr := gpu.io.tileRom.addr + Config.TILE_ROM_OFFSET.U
  io.debug.pc := cpu.io.debug.pc
  io.debug.pcw := cpu.io.debug.pcw
}
