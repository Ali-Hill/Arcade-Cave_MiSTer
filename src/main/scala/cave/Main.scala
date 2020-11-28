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
import axon.gpu._
import axon.mem._
import axon.snd.Audio
import axon.types._
import cave.dma._
import cave.types._
import chisel3._
import chisel3.util._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

/**
 * The top-level module.
 *
 * This module abstracts the rest of the arcade hardware from MiSTer-specific things (e.g. memory
 * multiplexer) that are not part of the original arcade hardware design.
 */
class Main extends Module {
  val io = IO(new Bundle {
    /** Video clock domain */
    val videoClock = Input(Clock())
    /** Video reset */
    val videoReset = Input(Bool())
    /** CPU clock domain */
    val cpuClock = Input(Clock())
    /** CPU reset */
    val cpuReset = Input(Bool())
    /** Player port */
    val player = new PlayerIO
    /** Video port */
    val video = Output(new VideoIO)
    /** DDR port */
    val ddr = BurstReadWriteMemIO(DDRArbiter.ADDR_WIDTH, DDRArbiter.DATA_WIDTH)
    /** Download port */
    val download = DownloadIO()
    /** RGB output */
    val rgb = Output(new RGB(Config.SCREEN_BITS_PER_CHANNEL))
    /** Audio port */
    val audio = Output(new Audio(Config.SAMPLE_WIDTH))
    /** Debug port */
    val debug = Output(new Bundle {
      val pc = UInt()
      val pcw = Bool()
    })
  })

  // Registers
  val swapReg = RegInit(false.B)

  // Video timing
  //
  // The video timing module runs in the video clock domain. It doesn't use the video reset signal, as the video timing
  // signals should always be generated. Otherwise, the progress bar won't be visible while the core is loading.
  val videoTiming = withClock(io.videoClock) { Module(new VideoTiming(Config.videoTimingConfig)) }
  io.video <> videoTiming.io

  // The swap register selects which frame buffer is being used for reading/writing pixel data. While one frame buffer
  // is being written to, the other is being read from.
  //
  // It gets toggled on the rising edge of the vertical blank signal.
  val vBlank = ShiftRegister(videoTiming.io.vBlank, 2)
  when(Util.rising(vBlank)) { swapReg := !swapReg }

  // DDR arbiter
  val arbiter = Module(new DDRArbiter)
  io.download <> arbiter.io.download
  io.ddr <> arbiter.io.ddr

  // Video DMA
  val videoDMA = Module(new VideoDMA(
    addr = Config.FRAME_BUFFER_OFFSET,
    numWords = Config.FRAME_BUFFER_DMA_NUM_WORDS,
    burstLength = Config.FRAME_BUFFER_DMA_BURST_LENGTH
  ))
  videoDMA.io.swap := swapReg
  videoDMA.io.ddr <> arbiter.io.fbFromDDR

  // Frame buffer DMA
  val fbDMA = Module(new FrameBufferDMA(
    addr = Config.FRAME_BUFFER_OFFSET,
    numWords = Config.FRAME_BUFFER_DMA_NUM_WORDS,
    burstLength = Config.FRAME_BUFFER_DMA_BURST_LENGTH
  ))
  fbDMA.io.swap := !swapReg
  fbDMA.io.ddr <> arbiter.io.fbToDDR

  // Video FIFO
  val videoFIFO = Module(new VideoFIFO)
  videoFIFO.io.videoClock := io.videoClock
  videoFIFO.io.videoReset := io.videoReset
  videoFIFO.io.video <> videoTiming.io
  videoFIFO.io.pixelData <> videoDMA.io.pixelData
  io.rgb := videoFIFO.io.rgb

  // Data freezer
  val progRomFreezer = Module(new DataFreezer(
    addrWidth = Config.CACHE_ADDR_WIDTH,
    dataWidth = Config.CACHE_DATA_WIDTH
  ))
  progRomFreezer.io.targetClock := io.cpuClock
  progRomFreezer.io.targetReset := io.cpuReset
  progRomFreezer.io.out <> arbiter.io.progRom

  // Cache memory
  //
  // The cache memory runs in the CPU clock domain.
  val progRomCache = withClockAndReset(io.cpuClock, io.cpuReset) {
    Module(new CacheMem(
      inAddrWidth = Config.PROG_ROM_ADDR_WIDTH+1, // byte addressing
      inDataWidth = Config.PROG_ROM_DATA_WIDTH,
      outAddrWidth = Config.CACHE_ADDR_WIDTH,
      outDataWidth = Config.CACHE_DATA_WIDTH,
      depth = 512
    ))
  }
  progRomCache.io.out.mapAddr(_+Config.PROG_ROM_OFFSET.U) <> progRomFreezer.io.in

  // Data freezer
  val soundRomFreezer = Module(new DataFreezer(
    addrWidth = Config.CACHE_ADDR_WIDTH,
    dataWidth = Config.CACHE_DATA_WIDTH
  ))
  soundRomFreezer.io.targetClock := io.cpuClock
  soundRomFreezer.io.targetReset := io.cpuReset
  soundRomFreezer.io.out <> arbiter.io.soundRom

  // Cache memory
  //
  // The cache memory runs in the CPU clock domain.
  val soundRomCache = withClockAndReset(io.cpuClock, io.cpuReset) {
    Module(new CacheMem(
      inAddrWidth = Config.SOUND_ROM_ADDR_WIDTH,
      inDataWidth = Config.SOUND_ROM_DATA_WIDTH,
      outAddrWidth = Config.CACHE_ADDR_WIDTH,
      outDataWidth = Config.CACHE_DATA_WIDTH,
      depth = 256
    ))
  }
  soundRomCache.io.out.mapAddr(_+Config.SOUND_ROM_OFFSET.U) <> soundRomFreezer.io.in

  // Cave
  val cave = Module(new Cave)
  cave.io.cpuClock := io.cpuClock
  cave.io.cpuReset := io.cpuReset
  cave.io.player := io.player
  // Convert program ROM address to a byte address
  cave.io.progRom.mapAddr(_ ## 0.U) <> progRomCache.io.in
  cave.io.soundRom <> soundRomCache.io.in
  cave.io.tileRom.mapAddr(_+Config.TILE_ROM_OFFSET.U) <> arbiter.io.tileRom
  cave.io.video := videoTiming.io
  cave.io.frameBuffer <> fbDMA.io.frameBuffer
  io.audio <> cave.io.audio

  // Start the frame buffer DMA when a frame is complete
  fbDMA.io.start := cave.io.frameDone

  // Debug outputs
  io.debug.pc := cave.io.debug.pc
  io.debug.pcw := cave.io.debug.pcw
}

object Main extends App {
  (new ChiselStage).execute(
    Array("--compiler", "verilog", "--target-dir", "quartus/rtl", "--output-file", "ChiselTop"),
    Seq(ChiselGeneratorAnnotation(() => new Main))
  )
}
