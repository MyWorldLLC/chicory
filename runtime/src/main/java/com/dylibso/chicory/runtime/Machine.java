package com.dylibso.chicory.runtime;

import com.dylibso.chicory.runtime.exceptions.WASMRuntimeException;
import com.dylibso.chicory.wasm.exceptions.ChicoryException;
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.Value;
import com.dylibso.chicory.wasm.types.ValueType;
import java.util.List;
import java.util.Stack;

/**
 * This is responsible for holding and interpreting the Wasm code.
 */
public class Machine {

    private static final System.Logger LOGGER = System.getLogger(Machine.class.getName());

    public static final double TWO_POW_63_D = 0x1.0p63; /* 2^63 */

    public static final float TWO_POW_64_PLUS_1_F = 1.8446743E19F; /* 2^64 + 1*/

    private final MStack stack;

    private final Stack<StackFrame> callStack;

    private final Instance instance;

    public Machine(Instance instance) {
        this.instance = instance;
        this.stack = new MStack();
        this.callStack = new Stack<>();
    }

    public Value[] call(int funcId, Value[] args, boolean popResults) throws ChicoryException {
        var func = instance.getFunction(funcId);
        if (func != null) {
            this.callStack.push(new StackFrame(instance, funcId, 0, args, func.getLocals()));
            eval(func.getInstructions());
        } else {
            this.callStack.push(new StackFrame(instance, funcId, 0, args, List.of()));
            var imprt = instance.getImports()[funcId];
            var hostFunc = imprt.getHandle();
            var results = hostFunc.apply(this.instance.getMemory(), args);
            // a host function can return null or an array of ints
            // which we will push onto the stack
            if (results != null) {
                for (var result : results) {
                    this.stack.push(result);
                }
            }
        }

        if (!this.callStack.isEmpty()) {
            this.callStack.pop();
        }

        if (!popResults) {
            return null;
        }

        var typeId = instance.getFunctionType(funcId);
        var type = instance.getTypes()[typeId];
        if (type.getReturns().length == 0) return null;
        if (this.stack.size() == 0) return null;

        var totalResults = type.getReturns().length;
        var results = new Value[totalResults];
        for (var i = totalResults - 1; i >= 0; i--) {
            results[i] = this.stack.pop();
        }
        return results;
    }

    void eval(List<Instruction> code) throws ChicoryException {
        try {
            var frame = callStack.peek();
            boolean shouldReturn = false;

            loop:
            while (frame.pc < code.size()) {
                if (shouldReturn) return;
                var instruction = code.get(frame.pc++);
                LOGGER.log(
                        System.Logger.Level.DEBUG,
                        "func="
                                + frame.funcId
                                + "@"
                                + frame.pc
                                + ": "
                                + instruction
                                + " stack="
                                + this.stack);
                var opcode = instruction.getOpcode();
                var operands = instruction.getOperands();
                switch (opcode) {
                    case UNREACHABLE:
                        throw new TrapException("Trapped on unreachable instruction", callStack);
                    case NOP:
                        break;
                    case LOOP:
                    case BLOCK:
                        {
                            frame.blockDepth++;

                            frame.isControlFrame = true;
                            frame.stackSizeBeforeBlock =
                                    Math.max(this.stack.size(), frame.stackSizeBeforeBlock);
                            var typeId = (int) operands[0];

                            // https://www.w3.org/TR/wasm-core-2/binary/instructions.html#binary-blocktype
                            if (typeId == 0x40) { // epsilon
                                frame.numberOfValuesToReturn =
                                        Math.max(frame.numberOfValuesToReturn, 0);
                            } else if (ValueType.byId(typeId)
                                    != null) { // shortcut to straight value type
                                frame.numberOfValuesToReturn =
                                        Math.max(frame.numberOfValuesToReturn, 1);
                            } else { // look it up
                                var funcType = instance.getTypes()[typeId];
                                frame.numberOfValuesToReturn =
                                        Math.max(
                                                frame.numberOfValuesToReturn,
                                                funcType.getReturns().length);
                            }

                            break;
                        }
                    case IF:
                        {
                            frame.blockDepth++;
                            frame.isControlFrame = false;

                            var pred = this.stack.pop().asInt();
                            if (pred == 0) {
                                frame.pc = instruction.getLabelFalse();
                            } else {
                                frame.pc = instruction.getLabelTrue();
                            }
                            break;
                        }
                    case ELSE:
                    case BR:
                        {
                            frame.doControlTransfer = true;

                            frame.pc = instruction.getLabelTrue();
                            break;
                        }
                    case BR_IF:
                        {
                            var predValue = this.stack.pop();
                            var pred = predValue.asInt();

                            if (pred == 0) {
                                frame.pc = instruction.getLabelFalse();
                            } else {
                                frame.doControlTransfer = true;
                                frame.branchConditionValue = predValue;
                                frame.pc = instruction.getLabelTrue();
                            }
                            break;
                        }
                    case BR_TABLE:
                        {
                            var predValue = this.stack.pop();
                            var pred = predValue.asInt();

                            frame.doControlTransfer = true;

                            if (pred < 0 || pred >= instruction.getLabelTable().length - 1) {
                                // choose default
                                frame.pc =
                                        instruction
                                                .getLabelTable()[
                                                instruction.getLabelTable().length - 1];
                            } else {
                                frame.branchConditionValue = predValue;
                                frame.pc = instruction.getLabelTable()[pred];
                            }

                            break;
                        }
                    case RETURN:
                        shouldReturn = true;
                        break;
                    case CALL_INDIRECT:
                        {
                            var tableIdx = (int) operands[1];
                            var table = instance.getTables()[tableIdx];
                            var funcTableIdx = this.stack.pop().asInt();
                            var funcId = table.getFuncRef(funcTableIdx);
                            var typeId = (int) operands[0];
                            var type = instance.getTypes()[typeId];
                            // given a list of param types, let's pop those params off the stack
                            // and pass as args to the function call
                            var args = extractArgsForParams(type.getParams());
                            call(funcId, args, false);
                            break;
                        }
                    case DROP:
                        this.stack.pop();
                        break;
                    case SELECT:
                        {
                            var pred = this.stack.pop().asInt();
                            var b = this.stack.pop();
                            var a = this.stack.pop();
                            if (pred == 0) {
                                this.stack.push(b);
                            } else {
                                this.stack.push(a);
                            }
                            break;
                        }
                    case END:
                        {
                            // if this is the last end, then we're done with
                            // the function
                            if (frame.blockDepth == 0) {
                                break loop;
                            }
                            frame.blockDepth--;

                            // control transfer happens on all blocks but not on the depth 0
                            if (frame.doControlTransfer && frame.isControlFrame) {
                                // reset the control transfer
                                frame.doControlTransfer = false;

                                var valuesToBePushedBack =
                                        Math.min(frame.numberOfValuesToReturn, this.stack.size());

                                // pop the values from the stack
                                Value[] tmp = new Value[valuesToBePushedBack];
                                for (int i = 0; i < valuesToBePushedBack; i++) {
                                    tmp[i] = this.stack.pop();
                                }

                                // drop everything till the previous label
                                while (this.stack.size() > frame.stackSizeBeforeBlock) {
                                    this.stack.pop();
                                }

                                // this is mostly empirical
                                // if a branch have been taken we restore the consumed value from
                                // the stack
                                if (frame.branchConditionValue != null
                                        && frame.branchConditionValue.asInt() > 0) {
                                    this.stack.push(frame.branchConditionValue);
                                }

                                // Push the values to the stack.
                                for (int i = valuesToBePushedBack - 1; i >= 0; i--) {
                                    this.stack.push(tmp[i]);
                                }
                            }

                            break;
                        }
                    case LOCAL_GET:
                        {
                            this.stack.push(frame.getLocal((int) operands[0]));
                            break;
                        }
                    case LOCAL_SET:
                        {
                            frame.setLocal((int) operands[0], this.stack.pop());
                            break;
                        }
                    case LOCAL_TEE:
                        {
                            // here we peek instead of pop, leaving it on the stack
                            frame.setLocal((int) operands[0], this.stack.peek());
                            break;
                        }
                    case GLOBAL_GET:
                        {
                            var ex = instance.getImports().length;
                            var val = instance.getGlobal((int) operands[0]);
                            this.stack.push(val);
                            break;
                        }
                    case GLOBAL_SET:
                        {
                            var id = (int) operands[0];
                            var global = instance.getGlobalInitalizer(id);
                            if (global.getMutabilityType() == MutabilityType.Const)
                                throw new RuntimeException(
                                        "Can't call GLOBAL_SET on immutable global");
                            var val = this.stack.pop();
                            instance.setGlobal(id, val);
                            break;
                        }
                        // TODO signed and unsigned are the same right now
                    case I32_LOAD:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getI32(ptr);
                            this.stack.push(val);
                            break;
                        }
                    case I64_LOAD:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getI64(ptr);
                            this.stack.push(val);
                            break;
                        }
                    case F32_LOAD:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getF32(ptr);
                            this.stack.push(val);
                            break;
                        }
                    case F64_LOAD:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getF64(ptr);
                            this.stack.push(val);
                            break;
                        }
                    case I32_LOAD8_S:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getI8(ptr);
                            this.stack.push(val);
                            break;
                        }
                    case I64_LOAD8_S:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getI8(ptr);
                            // TODO a bit hacky
                            this.stack.push(Value.i64(val.asInt()));
                            break;
                        }
                    case I32_LOAD8_U:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getI8U(ptr);
                            this.stack.push(val);
                            break;
                        }
                    case I64_LOAD8_U:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getI8U(ptr);
                            // TODO a bit hacky
                            this.stack.push(Value.i64(val.asInt()));
                            break;
                        }
                    case I32_LOAD16_S:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getI16(ptr);
                            this.stack.push(val);
                            break;
                        }
                    case I64_LOAD16_S:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getI16(ptr);
                            // TODO this is a bit hacky
                            this.stack.push(Value.i64(val.asInt()));
                            break;
                        }
                    case I32_LOAD16_U:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getU16(ptr);
                            this.stack.push(val);
                            break;
                        }
                    case I64_LOAD16_U:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getU16(ptr);
                            // TODO this is a bit hacky
                            this.stack.push(Value.i64(val.asInt()));
                            break;
                        }
                    case I64_LOAD32_S:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getI32(ptr);
                            // TODO this is a bit hacky
                            this.stack.push(Value.i64(val.asInt()));
                            break;
                        }
                    case I64_LOAD32_U:
                        {
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            var val = instance.getMemory().getU32(ptr);
                            this.stack.push(val);
                            break;
                        }
                    case I32_STORE:
                        {
                            var value = this.stack.pop().asInt();
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            instance.getMemory().putI32(ptr, value);
                            break;
                        }
                    case I32_STORE16:
                    case I64_STORE16:
                        {
                            var value = this.stack.pop().asShort();
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            instance.getMemory().putShort(ptr, value);
                            break;
                        }
                    case I64_STORE:
                        {
                            var value = this.stack.pop().asLong();
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            instance.getMemory().putI64(ptr, value);
                            break;
                        }
                    case F32_STORE:
                        {
                            var value = this.stack.pop().asFloat();
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            instance.getMemory().putF32(ptr, value);
                            break;
                        }
                    case F64_STORE:
                        {
                            var value = this.stack.pop().asDouble();
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            instance.getMemory().putF64(ptr, value);
                            break;
                        }
                    case MEMORY_GROW:
                        {
                            var size = stack.pop().asInt();
                            var nPages = instance.getMemory().grow(size);
                            stack.push(Value.i32(nPages));
                            break;
                        }
                    case I32_STORE8:
                    case I64_STORE8:
                        {
                            var value = this.stack.pop().asByte();
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            instance.getMemory().putByte(ptr, value);
                            break;
                        }
                    case I64_STORE32:
                        {
                            var value = this.stack.pop().asLong();
                            var ptr = (int) (operands[1] + this.stack.pop().asInt());
                            instance.getMemory().putI32(ptr, (int) value);
                            break;
                        }
                    case MEMORY_SIZE:
                        {
                            var sz = instance.getMemory().getSize();
                            this.stack.push(Value.i32(sz));
                            break;
                        }
                        // TODO 32bit and 64 bit operations are the same for now
                    case I32_CONST:
                        {
                            this.stack.push(Value.i32(operands[0]));
                            break;
                        }
                    case I64_CONST:
                        {
                            this.stack.push(Value.i64(operands[0]));
                            break;
                        }
                    case F32_CONST:
                        {
                            this.stack.push(Value.f32(operands[0]));
                            break;
                        }
                    case F64_CONST:
                        {
                            this.stack.push(Value.f64(operands[0]));
                            break;
                        }
                    case I32_EQ:
                        {
                            var a = stack.pop().asInt();
                            var b = stack.pop().asInt();
                            this.stack.push(a == b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_EQ:
                        {
                            var a = this.stack.pop().asLong();
                            var b = this.stack.pop().asLong();
                            this.stack.push(a == b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_NE:
                        {
                            var a = this.stack.pop().asInt();
                            var b = this.stack.pop().asInt();
                            this.stack.push(a == b ? Value.FALSE : Value.TRUE);
                            break;
                        }
                    case I64_NE:
                        {
                            var a = this.stack.pop().asLong();
                            var b = this.stack.pop().asLong();
                            this.stack.push(a == b ? Value.FALSE : Value.TRUE);
                            break;
                        }
                    case I32_EQZ:
                        {
                            var a = this.stack.pop().asInt();
                            this.stack.push(a == 0 ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_EQZ:
                        {
                            var a = this.stack.pop().asLong();
                            this.stack.push(a == 0L ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_LT_S:
                        {
                            var b = this.stack.pop().asInt();
                            var a = this.stack.pop().asInt();
                            this.stack.push(a < b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_LT_U:
                        {
                            var b = this.stack.pop().asUInt();
                            var a = this.stack.pop().asUInt();
                            this.stack.push(a < b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_LT_S:
                        {
                            var b = this.stack.pop().asLong();
                            var a = this.stack.pop().asLong();
                            this.stack.push(a < b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_LT_U:
                        {
                            var b = this.stack.pop().asULong();
                            var a = this.stack.pop().asULong();
                            this.stack.push(a.compareTo(b) < 0 ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_GT_S:
                        {
                            var b = this.stack.pop().asInt();
                            var a = this.stack.pop().asInt();
                            this.stack.push(a > b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_GT_U:
                        {
                            var b = this.stack.pop().asUInt();
                            var a = this.stack.pop().asUInt();
                            this.stack.push(a > b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_GT_S:
                        {
                            var b = this.stack.pop().asLong();
                            var a = this.stack.pop().asLong();
                            this.stack.push(a > b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_GT_U:
                        {
                            var b = this.stack.pop().asULong();
                            var a = this.stack.pop().asULong();
                            this.stack.push(a.compareTo(b) > 0 ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_GE_S:
                        {
                            var b = this.stack.pop().asInt();
                            var a = this.stack.pop().asInt();
                            this.stack.push(a >= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_GE_U:
                        {
                            var b = this.stack.pop().asUInt();
                            var a = this.stack.pop().asUInt();
                            this.stack.push(a >= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_GE_U:
                        {
                            var b = this.stack.pop().asULong();
                            var a = this.stack.pop().asULong();
                            this.stack.push(a.compareTo(b) >= 0 ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_GE_S:
                        {
                            var b = this.stack.pop().asLong();
                            var a = this.stack.pop().asLong();
                            this.stack.push(a >= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_LE_S:
                        {
                            var b = this.stack.pop().asInt();
                            var a = this.stack.pop().asInt();
                            this.stack.push(a <= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_LE_U:
                        {
                            var b = this.stack.pop().asUInt();
                            var a = this.stack.pop().asUInt();
                            this.stack.push(a <= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_LE_S:
                        {
                            var b = this.stack.pop().asLong();
                            var a = this.stack.pop().asLong();
                            this.stack.push(a <= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I64_LE_U:
                        {
                            var b = this.stack.pop().asULong();
                            var a = this.stack.pop().asULong();
                            this.stack.push(a.compareTo(b) <= 0 ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F32_EQ:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();
                            this.stack.push(a == b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F64_EQ:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();
                            this.stack.push(a == b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case I32_CLZ:
                        {
                            var tos = this.stack.pop().asInt();
                            var count = Integer.numberOfLeadingZeros(tos);
                            this.stack.push(Value.i32(count));
                            break;
                        }
                    case I32_CTZ:
                        {
                            var tos = this.stack.pop().asInt();
                            var count = Integer.numberOfTrailingZeros(tos);
                            this.stack.push(Value.i32(count));
                            break;
                        }
                    case I32_POPCNT:
                        {
                            var tos = this.stack.pop().asInt();
                            var count = Integer.bitCount(tos);
                            this.stack.push(Value.i32(count));
                            break;
                        }
                    case I32_ADD:
                        {
                            var a = this.stack.pop().asInt();
                            var b = this.stack.pop().asInt();
                            this.stack.push(Value.i32(a + b));
                            break;
                        }
                    case I64_ADD:
                        {
                            var a = this.stack.pop().asLong();
                            var b = this.stack.pop().asLong();
                            this.stack.push(Value.i64(a + b));
                            break;
                        }
                    case I32_SUB:
                        {
                            var a = this.stack.pop().asInt();
                            var b = this.stack.pop().asInt();
                            this.stack.push(Value.i32(b - a));
                            break;
                        }
                    case I64_SUB:
                        {
                            var a = this.stack.pop().asLong();
                            var b = this.stack.pop().asLong();
                            this.stack.push(Value.i64(b - a));
                            break;
                        }
                    case I32_MUL:
                        {
                            var a = this.stack.pop().asInt();
                            var b = this.stack.pop().asInt();
                            this.stack.push(Value.i32(a * b));
                            break;
                        }
                    case I64_MUL:
                        {
                            var a = this.stack.pop().asLong();
                            var b = this.stack.pop().asLong();
                            this.stack.push(Value.i64(a * b));
                            break;
                        }
                    case I32_DIV_S:
                        {
                            var b = this.stack.pop().asInt();
                            var a = this.stack.pop().asInt();
                            if (a == Integer.MIN_VALUE && b == -1) {
                                throw new WASMRuntimeException("integer overflow");
                            }
                            this.stack.push(Value.i32(a / b));
                            break;
                        }
                    case I32_DIV_U:
                        {
                            var b = this.stack.pop().asUInt();
                            var a = this.stack.pop().asUInt();
                            this.stack.push(Value.i32(a / b));
                            break;
                        }
                    case I64_DIV_S:
                        {
                            var b = this.stack.pop().asLong();
                            var a = this.stack.pop().asLong();
                            if (a == Long.MIN_VALUE && b == -1L) {
                                throw new WASMRuntimeException("integer overflow");
                            }
                            this.stack.push(Value.i64(a / b));
                            break;
                        }
                    case I64_DIV_U:
                        {
                            var b = this.stack.pop().asLong();
                            var a = this.stack.pop().asLong();
                            this.stack.push(Value.i64(Long.divideUnsigned(a, b)));
                            break;
                        }
                    case I32_REM_S:
                        {
                            var b = this.stack.pop().asInt();
                            var a = this.stack.pop().asInt();
                            this.stack.push(Value.i32(a % b));
                            break;
                        }
                    case I32_REM_U:
                        {
                            var b = this.stack.pop().asUInt();
                            var a = this.stack.pop().asUInt();
                            this.stack.push(Value.i32(a % b));
                            break;
                        }
                    case I64_AND:
                        {
                            var a = this.stack.pop().asLong();
                            var b = this.stack.pop().asLong();
                            this.stack.push(Value.i64(a & b));
                            break;
                        }
                    case I64_OR:
                        {
                            var a = this.stack.pop().asLong();
                            var b = this.stack.pop().asLong();
                            this.stack.push(Value.i64(a | b));
                            break;
                        }
                    case I64_XOR:
                        {
                            var a = this.stack.pop().asLong();
                            var b = this.stack.pop().asLong();
                            this.stack.push(Value.i64(a ^ b));
                            break;
                        }
                    case I64_SHL:
                        {
                            var c = this.stack.pop().asLong();
                            var v = this.stack.pop().asLong();
                            this.stack.push(Value.i64(v << c));
                            break;
                        }
                    case I64_SHR_S:
                        {
                            var c = this.stack.pop().asLong();
                            var v = this.stack.pop().asLong();
                            this.stack.push(Value.i64(v >> c));
                            break;
                        }
                    case I64_SHR_U:
                        {
                            var c = this.stack.pop().asLong();
                            var v = this.stack.pop().asLong();
                            this.stack.push(Value.i64(v >>> c));
                            break;
                        }
                    case I64_REM_S:
                        {
                            var b = this.stack.pop().asLong();
                            var a = this.stack.pop().asLong();
                            this.stack.push(Value.i64(a % b));
                            break;
                        }
                    case I64_REM_U:
                        {
                            var b = this.stack.pop().asLong();
                            var a = this.stack.pop().asLong();
                            this.stack.push(Value.i64(Long.remainderUnsigned(a, b)));
                            break;
                        }
                    case I64_ROTL:
                        {
                            var c = this.stack.pop().asLong();
                            var v = this.stack.pop().asLong();
                            var z = (v << c) | (v >>> (64 - c));
                            this.stack.push(Value.i64(z));
                            break;
                        }
                    case I64_ROTR:
                        {
                            var c = this.stack.pop().asLong();
                            var v = this.stack.pop().asLong();
                            var z = (v >>> c) | (v << (64 - c));
                            this.stack.push(Value.i64(z));
                            break;
                        }
                    case I64_CLZ:
                        {
                            var tos = this.stack.pop();
                            var count = Long.numberOfLeadingZeros(tos.asLong());
                            this.stack.push(Value.i64(count));
                            break;
                        }
                    case I64_CTZ:
                        {
                            var tos = this.stack.pop();
                            var count = Long.numberOfTrailingZeros(tos.asLong());
                            this.stack.push(Value.i64(count));
                            break;
                        }
                    case I64_POPCNT:
                        {
                            var tos = this.stack.pop().asLong();
                            var count = Long.bitCount(tos);
                            this.stack.push(Value.i64(count));
                            break;
                        }
                    case F32_NEG:
                        {
                            var tos = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat(-1.0f * tos));
                            break;
                        }
                    case F64_NEG:
                        {
                            var tos = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(-1.0d * tos));
                            break;
                        }
                    case CALL:
                        {
                            var funcId = (int) operands[0];
                            var typeId = instance.getFunctionType(funcId);
                            var type = instance.getTypes()[typeId];
                            // given a list of param types, let's pop those params off the stack
                            // and pass as args to the function call
                            var args = extractArgsForParams(type.getParams());
                            call(funcId, args, false);
                            break;
                        }
                    case I32_AND:
                        {
                            var a = this.stack.pop().asInt();
                            var b = this.stack.pop().asInt();
                            this.stack.push(Value.i32(a & b));
                            break;
                        }
                    case I32_OR:
                        {
                            var a = this.stack.pop().asInt();
                            var b = this.stack.pop().asInt();
                            this.stack.push(Value.i32(a | b));
                            break;
                        }
                    case I32_XOR:
                        {
                            var a = this.stack.pop().asInt();
                            var b = this.stack.pop().asInt();
                            this.stack.push(Value.i32(a ^ b));
                            break;
                        }
                    case I32_SHL:
                        {
                            var c = this.stack.pop().asInt();
                            var v = this.stack.pop().asInt();
                            this.stack.push(Value.i32(v << c));
                            break;
                        }
                    case I32_SHR_S:
                        {
                            var c = this.stack.pop().asInt();
                            var v = this.stack.pop().asInt();
                            this.stack.push(Value.i32(v >> c));
                            break;
                        }
                    case I32_SHR_U:
                        {
                            var c = this.stack.pop().asInt();
                            var v = this.stack.pop().asInt();
                            this.stack.push(Value.i32(v >>> c));
                            break;
                        }
                    case I32_ROTL:
                        {
                            var c = this.stack.pop().asInt();
                            var v = this.stack.pop().asInt();
                            var z = (v << c) | (v >>> (32 - c));
                            this.stack.push(Value.i32(z));
                            break;
                        }
                    case I32_ROTR:
                        {
                            var c = this.stack.pop().asInt();
                            var v = this.stack.pop().asInt();
                            var z = (v >>> c) | (v << (32 - c));
                            this.stack.push(Value.i32(z));
                            break;
                        }
                    case F32_ADD:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat(a + b));
                            break;
                        }
                    case F64_ADD:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(a + b));
                            break;
                        }
                    case F32_SUB:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat(b - a));
                            break;
                        }
                    case F64_SUB:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(b - a));
                            break;
                        }
                    case F32_MUL:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat(b * a));
                            break;
                        }
                    case F64_MUL:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(b * a));
                            break;
                        }
                    case F32_DIV:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat(b / a));
                            break;
                        }
                    case F64_DIV:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(b / a));
                            break;
                        }
                    case F32_MIN:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat(Math.min(a, b)));
                            break;
                        }
                    case F64_MIN:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(Math.min(a, b)));
                            break;
                        }
                    case F32_MAX:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat(Math.max(a, b)));
                            break;
                        }
                    case F64_MAX:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(Math.max(a, b)));
                            break;
                        }
                    case F32_SQRT:
                        {
                            var val = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat((float) Math.sqrt(val)));
                            break;
                        }
                    case F64_SQRT:
                        {
                            var val = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(Math.sqrt(val)));
                            break;
                        }
                    case F32_FLOOR:
                        {
                            var val = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat((float) Math.floor(val)));
                            break;
                        }
                    case F64_FLOOR:
                        {
                            var val = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(Math.floor(val)));
                            break;
                        }
                    case F32_CEIL:
                        {
                            var val = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat((float) Math.ceil(val)));
                            break;
                        }
                    case F64_CEIL:
                        {
                            var val = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(Math.ceil(val)));
                            break;
                        }
                    case F32_TRUNC:
                        {
                            var val = this.stack.pop().asFloat();
                            this.stack.push(
                                    Value.fromFloat(
                                            (float)
                                                    ((val < 0)
                                                            ? Math.ceil(val)
                                                            : Math.floor(val))));
                            break;
                        }
                    case F64_TRUNC:
                        {
                            var val = this.stack.pop().asDouble();
                            this.stack.push(
                                    Value.fromDouble((val < 0) ? Math.ceil(val) : Math.floor(val)));
                            break;
                        }
                    case F32_NEAREST:
                        {
                            var val = this.stack.pop().asFloat();
                            this.stack.push(Value.fromFloat((float) Math.rint(val)));
                            break;
                        }
                    case F64_NEAREST:
                        {
                            var val = this.stack.pop().asDouble();
                            this.stack.push(Value.fromDouble(Math.rint(val)));
                            break;
                        }
                        // For the extend_* operations, note that java
                        // automatically does this when casting from
                        // smaller to larger primitives
                    case I32_EXTEND_8_S:
                        {
                            var tos = this.stack.pop().asByte();
                            this.stack.push(Value.i32(tos));
                            break;
                        }
                    case I32_EXTEND_16_S:
                        {
                            var original = this.stack.pop().asInt() & 0xFFFF;
                            if ((original & 0x8000) != 0) original |= 0xFFFF0000;
                            this.stack.push(Value.i32(original & 0xFFFFFFFFL));
                            break;
                        }
                    case I64_EXTEND_8_S:
                        {
                            var tos = this.stack.pop().asByte();
                            this.stack.push(Value.i64(tos));
                            break;
                        }
                    case I64_EXTEND_16_S:
                        {
                            var tos = this.stack.pop().asShort();
                            this.stack.push(Value.i64(tos));
                            break;
                        }
                    case I64_EXTEND_32_S:
                        {
                            var tos = this.stack.pop().asInt();
                            this.stack.push(Value.i64(tos));
                            break;
                        }
                    case F64_CONVERT_I64_U:
                        {
                            var tos = this.stack.pop().asULong();
                            this.stack.push(Value.fromDouble(tos.doubleValue()));
                            break;
                        }
                    case F64_CONVERT_I32_U:
                        {
                            long tos = this.stack.pop().asUInt();
                            this.stack.push(Value.f64(Double.doubleToRawLongBits(tos)));
                            break;
                        }
                    case F64_CONVERT_I32_S:
                        {
                            var tos = this.stack.pop();
                            this.stack.push(Value.fromDouble(tos.asInt()));
                            break;
                        }
                    case F64_PROMOTE_F32:
                        {
                            var tos = this.stack.pop();
                            this.stack.push(Value.fromDouble(tos.asFloat()));
                            break;
                        }
                    case F64_REINTERPRET_I64:
                        {
                            var tos = this.stack.pop();
                            this.stack.push(Value.f64(tos.asLong()));
                            break;
                        }
                    case I64_TRUNC_F64_S:
                        {
                            double tos = this.stack.pop().asDouble();

                            if (Double.isNaN(tos)) {
                                throw new WASMRuntimeException("invalid conversion to integer");
                            }

                            long tosL = (long) tos;
                            if (tos == (double) Long.MIN_VALUE) {
                                tosL = Long.MIN_VALUE;
                            } else if (tosL == Long.MIN_VALUE || tosL == Long.MAX_VALUE) {
                                throw new WASMRuntimeException("integer overflow");
                            }

                            this.stack.push(Value.i64(tosL));
                            break;
                        }
                    case I32_WRAP_I64:
                        {
                            var tos = this.stack.pop();
                            this.stack.push(Value.i32(tos.asInt()));
                            break;
                        }
                    case I64_EXTEND_I32_S:
                        {
                            var tos = this.stack.pop();
                            this.stack.push(Value.i64(tos.asInt()));
                            break;
                        }
                    case I64_EXTEND_I32_U:
                        {
                            var tos = this.stack.pop();
                            this.stack.push(Value.i64(tos.asUInt()));
                            break;
                        }
                    case I32_REINTERPRET_F32:
                        {
                            var tos = this.stack.pop();
                            this.stack.push(Value.i32(tos.asInt()));
                            break;
                        }
                    case I64_REINTERPRET_F64:
                        {
                            var tos = this.stack.pop();
                            this.stack.push(Value.i64(tos.asLong()));
                            break;
                        }
                    case F32_REINTERPRET_I32:
                        {
                            var tos = this.stack.pop();
                            this.stack.push(Value.f32(tos.asInt()));
                            break;
                        }
                    case F32_COPYSIGN:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();

                            if (a == 0xFFC00000L) { // +NaN
                                this.stack.push(Value.fromFloat(Math.copySign(b, -1)));
                            } else if (a == 0x7FC00000L) { // -NaN
                                this.stack.push(Value.fromFloat(Math.copySign(b, +1)));
                            } else {
                                this.stack.push(Value.fromFloat(Math.copySign(b, a)));
                            }
                            break;
                        }
                    case F32_ABS:
                        {
                            var val = this.stack.pop().asFloat();

                            this.stack.push(Value.fromFloat(Math.abs(val)));
                            break;
                        }
                    case F64_COPYSIGN:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();

                            if (a == 0xFFC0000000000000L) { // +NaN
                                this.stack.push(Value.fromDouble(Math.copySign(b, -1)));
                            } else if (a == 0x7FC0000000000000L) { // -NaN
                                this.stack.push(Value.fromDouble(Math.copySign(b, +1)));
                            } else {
                                this.stack.push(Value.fromDouble(Math.copySign(b, a)));
                            }
                            break;
                        }
                    case F64_ABS:
                        {
                            var val = this.stack.pop().asDouble();

                            this.stack.push(Value.fromDouble(Math.abs(val)));
                            break;
                        }
                    case F32_NE:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();

                            this.stack.push(a == b ? Value.FALSE : Value.TRUE);
                            break;
                        }
                    case F64_NE:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();

                            this.stack.push(a == b ? Value.FALSE : Value.TRUE);
                            break;
                        }
                    case F32_LT:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();

                            this.stack.push(a > b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F64_LT:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();

                            this.stack.push(a > b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F32_LE:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();

                            this.stack.push(a >= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F64_LE:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();

                            this.stack.push(a >= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F32_GE:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();

                            this.stack.push(a <= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F64_GE:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();

                            this.stack.push(a <= b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F32_GT:
                        {
                            var a = this.stack.pop().asFloat();
                            var b = this.stack.pop().asFloat();

                            this.stack.push(a < b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F64_GT:
                        {
                            var a = this.stack.pop().asDouble();
                            var b = this.stack.pop().asDouble();

                            this.stack.push(a < b ? Value.TRUE : Value.FALSE);
                            break;
                        }
                    case F32_DEMOTE_F64:
                        {
                            var val = this.stack.pop().asDouble();

                            this.stack.push(Value.fromFloat((float) val));
                            break;
                        }
                    case F32_CONVERT_I32_S:
                        {
                            var tos = this.stack.pop().asInt();
                            this.stack.push(Value.fromFloat((float) tos));
                            break;
                        }
                    case I32_TRUNC_F32_S:
                        {
                            float tos = this.stack.pop().asFloat();

                            if (Float.isNaN(tos)) {
                                throw new WASMRuntimeException("invalid conversion to integer");
                            }

                            if (tos < Integer.MIN_VALUE || tos >= Integer.MAX_VALUE) {
                                throw new WASMRuntimeException("integer overflow");
                            }

                            this.stack.push(Value.i32((long) tos));
                            break;
                        }

                    case I32_TRUNC_SAT_F32_S:
                        {
                            var tos = this.stack.pop().asFloat();

                            if (Float.isNaN(tos)) {
                                tos = 0;
                            } else if (tos < Integer.MIN_VALUE) {
                                tos = Integer.MIN_VALUE;
                            } else if (tos > Integer.MAX_VALUE) {
                                tos = Integer.MAX_VALUE;
                            }

                            this.stack.push(Value.i32((int) tos));
                            break;
                        }
                    case I32_TRUNC_SAT_F32_U:
                        {
                            var tos = this.stack.pop().asFloat();

                            long tosL;
                            if (Float.isNaN(tos) || tos < 0) {
                                tosL = 0L;
                            } else if (tos >= 0xFFFFFFFFL) {
                                tosL = 0xFFFFFFFFL;
                            } else {
                                tosL = (long) tos;
                            }

                            this.stack.push(Value.i32(tosL));
                            break;
                        }

                    case I32_TRUNC_SAT_F64_S:
                        {
                            var tos = this.stack.pop().asDouble();

                            if (Double.isNaN(tos)) {
                                tos = 0;
                            } else if (tos <= Integer.MIN_VALUE) {
                                tos = Integer.MIN_VALUE;
                            } else if (tos >= Integer.MAX_VALUE) {
                                tos = Integer.MAX_VALUE;
                            }

                            this.stack.push(Value.i32((int) tos));
                            break;
                        }
                    case I32_TRUNC_SAT_F64_U:
                        {
                            double tos = Double.longBitsToDouble(this.stack.pop().asLong());

                            long tosL;
                            if (Double.isNaN(tos) || tos < 0) {
                                tosL = 0;
                            } else if (tos > 0xFFFFFFFFL) {
                                tosL = 0xFFFFFFFFL;
                            } else {
                                tosL = (long) tos;
                            }
                            this.stack.push(Value.i32(tosL));
                            break;
                        }
                    case F32_CONVERT_I32_U:
                        {
                            var tos = this.stack.pop().asUInt();

                            this.stack.push(Value.fromFloat((float) tos));
                            break;
                        }
                    case I32_TRUNC_F32_U:
                        {
                            var tos = this.stack.pop().asFloat();

                            if (Float.isNaN(tos)) {
                                throw new WASMRuntimeException("invalid conversion to integer");
                            }

                            long tosL = (long) tos;
                            if (tosL < 0 || tosL >= 0xFFFFFFFFL) {
                                throw new WASMRuntimeException("integer overflow");
                            }

                            this.stack.push(Value.i32(tosL));
                            break;
                        }
                    case F32_CONVERT_I64_S:
                        {
                            var tos = this.stack.pop().asLong();

                            this.stack.push(Value.fromFloat((float) tos));
                            break;
                        }
                    case F32_CONVERT_I64_U:
                        {
                            var tos = this.stack.pop().asULong();
                            float tosF;
                            if (tos.floatValue() < 0) {
                                /*
                                (the BigInteger is large, sign bit is set), tos.longValue() gets the lower 64 bits of the BigInteger (as a signed long),
                                and 0x1.0p63 (which is 2^63 in floating-point notation) is added to adjust the float value back to the unsigned range.
                                 */
                                tosF = (float) (tos.longValue() + TWO_POW_63_D);
                            } else {
                                tosF = tos.floatValue();
                            }
                            this.stack.push(Value.f32(Float.floatToIntBits(tosF)));
                            break;
                        }
                    case F64_CONVERT_I64_S:
                        {
                            var tos = this.stack.pop().asLong();

                            this.stack.push(Value.fromDouble((double) tos));
                            break;
                        }
                    case I64_TRUNC_F32_U:
                        {
                            var tos = this.stack.pop().asFloat();

                            if (Float.isNaN(tos)) {
                                throw new WASMRuntimeException("invalid conversion to integer");
                            }

                            var tosL = (long) tos;

                            if (tosL < 0 || (tosL == Long.MAX_VALUE)) {
                                throw new WASMRuntimeException("integer overflow");
                            }

                            this.stack.push(Value.i64(tosL));
                            break;
                        }
                    case I64_TRUNC_F64_U:
                        {
                            var tos = this.stack.pop().asDouble();

                            if (Double.isNaN(tos)) {
                                throw new WASMRuntimeException("invalid conversion to integer");
                            }
                            var tosL = (long) tos;
                            if (tos == (double) Long.MAX_VALUE) {
                                tosL = Long.MIN_VALUE;
                            } else if (tosL < 0 || tosL == Long.MAX_VALUE) {
                                throw new WASMRuntimeException("integer overflow");
                            }
                            this.stack.push(Value.i64(tosL));
                            break;
                        }

                    case I64_TRUNC_SAT_F32_S:
                        {
                            var tos = this.stack.pop().asFloat();

                            if (Float.isNaN(tos)) {
                                tos = 0;
                            } else if (tos <= Long.MIN_VALUE) {
                                tos = Long.MIN_VALUE;
                            } else if (tos >= Long.MAX_VALUE) {
                                tos = Long.MAX_VALUE;
                            }

                            this.stack.push(Value.i64((long) tos));
                            break;
                        }
                    case I64_TRUNC_SAT_F32_U:
                        {
                            var tos = this.stack.pop().asFloat();

                            long tosL;
                            if (Float.isNaN(tos) || tos < 0) {
                                tosL = 0L;
                            } else if (tos >= Long.MAX_VALUE) {
                                tosL = 0xFFFFFFFFFFFFFFFFL;
                            } else {
                                tosL = (long) tos;
                            }

                            this.stack.push(Value.i64(tosL));
                            break;
                        }
                    case I64_TRUNC_SAT_F64_S:
                        {
                            var tos = this.stack.pop().asDouble();

                            if (Double.isNaN(tos)) {
                                tos = 0;
                            } else if (tos <= Long.MIN_VALUE) {
                                tos = Long.MIN_VALUE;
                            } else if (tos >= Long.MAX_VALUE) {
                                tos = Long.MAX_VALUE;
                            }

                            this.stack.push(Value.i64((long) tos));
                            break;
                        }

                    case I64_TRUNC_SAT_F64_U:
                        {
                            double tos = this.stack.pop().asDouble();

                            long tosL;
                            if (Double.isNaN(tos) || tos <= -1.0) {
                                tosL = 0L;
                            } else if (tos >= TWO_POW_64_PLUS_1_F) {
                                tosL = 0xFFFFFFFFFFFFFFFFL;
                            } else if (tos == Long.MAX_VALUE) {
                                tosL = (long) tos + 1;
                            } else {
                                tosL = (long) tos;
                            }

                            this.stack.push(Value.i64(tosL));
                            break;
                        }

                    case I32_TRUNC_F64_S:
                        {
                            var tos = this.stack.pop().asDouble();

                            if (Double.isNaN(tos)) {
                                throw new WASMRuntimeException("invalid conversion to integer");
                            }

                            var tosL = (long) tos;
                            if (tosL < Integer.MIN_VALUE || tosL > Integer.MAX_VALUE) {
                                throw new WASMRuntimeException("integer overflow");
                            }

                            this.stack.push(Value.i32(tosL));
                            break;
                        }
                    case I32_TRUNC_F64_U:
                        {
                            double tos = this.stack.pop().asDouble();
                            if (Double.isNaN(tos)) {
                                throw new WASMRuntimeException("invalid conversion to integer");
                            }

                            var tosL = (long) tos;
                            if (tosL < 0 || tosL > 0xFFFFFFFFL) {
                                throw new WASMRuntimeException("integer overflow");
                            }
                            this.stack.push(Value.i32(tosL & 0xFFFFFFFFL));
                            break;
                        }
                    case I64_TRUNC_F32_S:
                        {
                            var tos = this.stack.pop().asFloat();

                            if (Float.isNaN(tos)) {
                                throw new WASMRuntimeException("invalid conversion to integer");
                            }

                            if (tos < Long.MIN_VALUE || tos >= Long.MAX_VALUE) {
                                throw new WASMRuntimeException("integer overflow");
                            }

                            this.stack.push(Value.i64((long) tos));
                            break;
                        }
                    case MEMORY_INIT:
                        {
                            var segmentId = (int) operands[0];
                            var memidx = (int) operands[1];
                            if (memidx != 0)
                                throw new WASMRuntimeException(
                                        "We don't support non zero index for memory: " + memidx);
                            var size = this.stack.pop().asInt();
                            var offset = this.stack.pop().asInt();
                            var destination = this.stack.pop().asInt();
                            instance.getMemory()
                                    .initPassiveSegment(segmentId, destination, offset, size);
                            break;
                        }
                    case DATA_DROP:
                        {
                            // do nothing
                            // TODO we'll need to tell the segment it's been dropped which changes
                            // the behavior
                            // next time we try to do memory.init
                            break;
                        }
                    case MEMORY_COPY:
                        {
                            var memidxSrc = (int) operands[0];
                            var memidxDst = (int) operands[1];
                            if (memidxDst != 0 && memidxSrc != 0)
                                throw new WASMRuntimeException(
                                        "We don't support non zero index for memory: "
                                                + memidxSrc
                                                + " "
                                                + memidxDst);
                            var size = this.stack.pop().asInt();
                            var offset = this.stack.pop().asInt();
                            var destination = this.stack.pop().asInt();
                            instance.getMemory().copy(destination, offset, size);
                            break;
                        }
                    default:
                        throw new RuntimeException(
                                "Machine doesn't recognize Instruction " + instruction);
                }
            }
        } catch (ChicoryException e) {
            // propagate ChicoryExceptions
            throw e;
        } catch (ArithmeticException e) {
            if (e.getMessage().equalsIgnoreCase("/ by zero")
                    || e.getMessage()
                            .contains("divide by zero")) { // On Linux i64 throws "BigInteger divide
                // by zero"
                throw new WASMRuntimeException("integer divide by zero: " + e.getMessage(), e);
            }
            throw new WASMRuntimeException(e.getMessage(), e);
        } catch (IndexOutOfBoundsException e) {
            throw new WASMRuntimeException("undefined element: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new WASMRuntimeException("An underlying Java exception occurred", e);
        }
    }

    public void printStackTrace() {
        LOGGER.log(System.Logger.Level.ERROR, "Trapped. Stacktrace:");
        for (var f : callStack) {
            LOGGER.log(System.Logger.Level.ERROR, f);
        }
    }

    Value[] extractArgsForParams(ValueType[] params) {
        if (params == null) {
            return Value.EMPTY_VALUES;
        }
        var args = new Value[params.length];
        for (var i = params.length; i > 0; i--) {
            var p = this.stack.pop();
            var t = params[i - 1];
            if (p.getType() != t) {
                throw new RuntimeException("Type error when extracting args.");
            }
            args[i - 1] = p;
        }
        return args;
    }
}
