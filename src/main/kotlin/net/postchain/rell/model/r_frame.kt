package net.postchain.rell.model

import net.postchain.rell.compiler.C_CompilerPass
import net.postchain.rell.compiler.C_LateInit
import net.postchain.rell.runtime.*

data class R_VarPtr(val name: String, val blockUid: R_FrameBlockUid, val offset: Int) {
    override fun toString() = "$blockUid/Var[$name,$offset]"
}

class R_FrameBlock(val parentUid: R_FrameBlockUid?, val uid: R_FrameBlockUid, val offset: Int, val size: Int)

class R_CallFrame(val defId: R_DefinitionId, val size: Int, val rootBlock: R_FrameBlock, val hasGuardBlock: Boolean) {
    fun createRtFrame(defCtx: Rt_DefinitionContext, caller: Rt_FrameCaller?, state: Rt_CallFrameState?): Rt_CallFrame {
        return Rt_CallFrame(defCtx, this, caller, state, hasGuardBlock)
    }

    companion object {
        private val ERROR_BLOCK = R_FrameBlock(null, R_Utils.ERROR_BLOCK_UID, -1, -1)
        val ERROR = R_CallFrame(R_DefinitionId.ERROR, 0, ERROR_BLOCK, false)

        val NONE_INIT_FRAME_GETTER = C_LateInit(C_CompilerPass.FRAMES, ERROR).getter
    }
}

class R_LambdaBlock(private val block: R_FrameBlock, private val varPtr: R_VarPtr, private val varType: R_Type) {
    fun <T> execute(frame: Rt_CallFrame, value: Rt_Value, body: () -> T): T {
        val res = frame.block(block) {
            frame.set(varPtr, varType, value, false)
            body()
        }
        return res
    }
}
