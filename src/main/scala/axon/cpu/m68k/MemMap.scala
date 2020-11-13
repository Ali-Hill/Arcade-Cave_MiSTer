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

package axon.cpu.m68k

import axon.Util
import axon.mem._
import cave.types.ProgRomIO
import chisel3._

/**
 * Represents a memory mapped address range.
 *
 * @param cpu The CPU.
 * @param r The address range.
 */
class MemMap(cpu: CPU, r: Range) {
  // TODO: These registers will be duplicated for every mapping. Can they be shared somehow?
  private val readStrobe = Util.rising(cpu.io.as) && cpu.io.rw
  private val writeStrobe = Util.rising(cpu.io.as) && !cpu.io.rw
  private val upperWriteStrobe = Util.rising(cpu.io.uds) && !cpu.io.rw
  private val lowerWriteStrobe = Util.rising(cpu.io.lds) && !cpu.io.rw

  /**
   * Maps an address range to the given read-write memory port.
   *
   * @param mem The memory port.
   */
  def ram(mem: ReadWriteMemIO): Unit = ramT(mem)(identity)

  /**
   * Maps an address range to the given read-write memory port, with an address transform.
   *
   * @param mem The memory port.
   * @param f The address transform function.
   */
  def ramT(mem: ReadWriteMemIO)(f: UInt => UInt): Unit = {
    val cs = Util.between(cpu.io.addr, r)
    mem.rd := cs && readStrobe
    mem.wr := cs && (upperWriteStrobe || lowerWriteStrobe)
    mem.addr := f(cpu.io.addr)(mem.addrWidth, 1)
    mem.mask := cpu.io.uds ## cpu.io.lds
    mem.din := cpu.io.dout
    when(cs) {
      cpu.io.din := mem.dout
      cpu.io.dtack := RegNext(readStrobe || upperWriteStrobe || lowerWriteStrobe)
    }
  }

  /**
   * Maps an address range to the given read-only memory port.
   *
   * @param mem The memory port.
   */
  def rom(mem: ProgRomIO): Unit = romT(mem)(identity)

  /**
   * Maps an address range to the given read-only memory port, with an address transform.
   *
   * @param mem The memory port.
   * @param f The address transform function.
   */
  def romT(mem: ProgRomIO)(f: UInt => UInt): Unit = {
    val cs = Util.between(cpu.io.addr, r)
    mem.rd := cs && readStrobe
    mem.addr := f(cpu.io.addr)
    when(cs) {
      cpu.io.din := mem.dout
      cpu.io.dtack := mem.valid
    }
  }

  /**
   * Maps an address range to the given write-only memory port.
   *
   * @param mem The memory port.
   */
  def wom(mem: WriteMemIO): Unit = womT(mem)(identity)

  /**
   * Maps an address range to the given write-only memory port, with an address transform.
   *
   * @param mem The memory port.
   * @param f The address transform function.
   */
  def womT(mem: WriteMemIO)(f: UInt => UInt): Unit = {
    val cs = Util.between(cpu.io.addr, r)
    mem.wr := cs && (upperWriteStrobe || lowerWriteStrobe)
    mem.addr := f(cpu.io.addr)
    mem.mask := cpu.io.uds ## cpu.io.lds
    mem.din := cpu.io.dout
    when(cs) {
      cpu.io.dtack := RegNext(upperWriteStrobe || lowerWriteStrobe)
    }
  }

  /**
   * Maps an address range to the given getter function.
   *
   * @param f The getter function.
   */
  def r(f: (UInt, UInt) => UInt): Unit = {
    val cs = Util.between(cpu.io.addr, r)
    val offset = cpu.io.addr - r.start.U
    when(cs && readStrobe) { cpu.io.din := f(cpu.io.addr, offset) }
  }

  /**
   * Maps an address range to the given setter function.
   *
   * @param f The setter function.
   */
  def w(f: (UInt, UInt, UInt) => Unit): Unit = {
    val cs = Util.between(cpu.io.addr, r)
    val offset = cpu.io.addr - r.start.U
    when(cs && writeStrobe) { f(cpu.io.addr, offset, cpu.io.dout) }
  }
}
