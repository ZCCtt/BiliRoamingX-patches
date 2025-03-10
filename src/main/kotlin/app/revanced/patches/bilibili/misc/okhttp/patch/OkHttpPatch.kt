package app.revanced.patches.bilibili.misc.okhttp.patch

import app.revanced.util.exception
import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.revanced.patches.bilibili.misc.okhttp.fingerprints.*
import app.revanced.patches.bilibili.patcher.patch.MultiMethodBytecodePatch
import app.revanced.patches.bilibili.utils.*
import com.android.tools.smali.dexlib2.AccessFlags

@Patch(
    name = "OkHttp",
    description = "OkHttp网络请求响应Hook",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object OkHttpPatch : MultiMethodBytecodePatch(
    fingerprints = setOf(
        HttpUrlFingerprint,
        MediaTypeGetFingerprint,
        RequestFingerprint,
        ResponseBodyFingerprint,
        ResponseFingerprint,
        BufferFingerprint,
        HeadersFingerprint,
        RealCallFingerprint,
    ),
    multiFingerprints = setOf(BodyWrapperFingerprint)
) {
    override fun execute(context: BytecodeContext) {
        super.execute(context)
        val httpUrlClass = HttpUrlFingerprint.result?.classDef
            ?: throw HttpUrlFingerprint.exception
        val requestClass = RequestFingerprint.result?.mutableClass
            ?: throw RequestFingerprint.exception
        requestClass.fields.forEach { it.accessFlags = it.accessFlags.toPublic() }
        val urlField = requestClass.fields.first { it.type == httpUrlClass.type }
        val requestHeaderMethod = requestClass.methods.first {
            it.returnType == "Ljava/lang/String;" && it.parameterTypes == listOf("Ljava/lang/String;")
        }
        val requestBodyField = requestClass.fields.first { f ->
            context.findClass { it.type == f.type }?.immutableClass?.accessFlags?.isAbstract() ?: false
        }
        val requestBodyClass = context.findClass { it.type == requestBodyField.type }!!.immutableClass
        val writeToMethod = requestBodyClass.methods.first { m ->
            m.accessFlags.isAbstract() && m.returnType == "V" && m.parameterTypes.size == 1
        }
        val bufferClass = BufferFingerprint.result?.classDef
            ?: throw BufferFingerprint.exception
        val bufferInStreamMethod = bufferClass.methods.first {
            it.returnType == "Ljava/io/InputStream;" && it.parameterTypes.isEmpty()
        }
        val responseClass = ResponseFingerprint.result?.mutableClass
            ?: throw ResponseFingerprint.exception
        responseClass.fields.forEach { it.accessFlags = it.accessFlags.removeFinal().toPublic() }
        val responseHeaderMethod = responseClass.methods.first {
            it.returnType == "Ljava/lang/String;" && it.parameterTypes == listOf("Ljava/lang/String;")
        }
        val requestField = responseClass.fields.first { it.type == requestClass.type }
        val codeField = responseClass.fields.first { it.type == "I" }
        val responseBodyClass = ResponseBodyFingerprint.result?.classDef
            ?: throw ResponseBodyFingerprint.exception
        val bodyStreamMethod = responseBodyClass.methods.first {
            it.returnType == "Ljava/io/InputStream;" && it.parameterTypes.isEmpty()
        }
        val responseBodyField = responseClass.fields.first { it.type == responseBodyClass.type }
        val mediaTypeGetMethod = MediaTypeGetFingerprint.result?.method
            ?: throw MediaTypeGetFingerprint.exception
        val mediaTypeType = mediaTypeGetMethod.definingClass
        val createMethod = responseBodyClass.methods.first { m ->
            AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.let { ts ->
                ts.size == 2 && ts[0] == mediaTypeType && ts[1] == "Ljava/lang/String;"
            }
        }
        val requestBodyTypeMethod = requestBodyClass.methods.first {
            it.returnType == mediaTypeType && it.parameterTypes.isEmpty()
        }
        val bodyWrapperClasses = BodyWrapperFingerprint.result.map { it.mutableClass }
            .onEach { it.accessFlags = it.accessFlags.toPublic() }
        val headersClass = HeadersFingerprint.result?.mutableClass
            ?: throw HeadersFingerprint.exception
        val headersConstructor = headersClass.methods.first {
            it.name == "<init>" && it.parameterTypes == listOf("[Ljava/lang/String;")
        }.also { it.accessFlags = it.accessFlags.toPublic() }
        val headersValueField = headersClass.fields.first {
            it.type == "[Ljava/lang/String;"
        }.also { it.accessFlags = it.accessFlags.toPublic() }
        val responseHeadersField = responseClass.fields.first {
            it.type == headersClass.type
        }
        val removeHeaderMethod = context.findClass("Lapp/revanced/bilibili/utils/Utils;")!!
            .immutableClass.methods.first { it.name == "removeHeader" }
        val realCallResult = RealCallFingerprint.result
            ?: throw RealCallFingerprint.exception
        val realCallClass = realCallResult.mutableClass
        val realCallGetMethod = realCallResult.mutableMethod
        val hookMethod = method(
            definingClass = realCallClass.type,
            name = "hook",
            returnType = "V",
            parameters = listOf(methodParameter(responseClass.type)),
            accessFlags = AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
            implementation = methodImplementation(registerCount = 21)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0, """
                move-object/from16 v0, p0
            
                iget-object v1, v0, $responseBodyField
            
                if-eqz v1, :cond_ab
                
                ${
                    bodyWrapperClasses.joinToString(separator = "\n") {
                        """
                        instance-of v2, v1, $it
                        if-nez v2, :cond_ab
                    """.trimIndent()
                    }
                }
            
                iget-object v2, v0, $requestField
            
                iget v9, v0, $codeField
            
                iget-object v3, v2, $urlField
            
                invoke-virtual {v3}, Ljava/lang/Object;->toString()Ljava/lang/String;
            
                move-result-object v10
            
                invoke-static {v10, v9}, Lapp/revanced/bilibili/patches/okhttp/OkHttpPatch;->shouldHook(Ljava/lang/String;I)Z
            
                move-result v3
            
                if-eqz v3, :cond_ab
            
                const-string v3, "Content-Type"
            
                invoke-virtual {v0, v3}, $responseHeaderMethod
            
                move-result-object v11
            
                if-eqz v11, :cond_ab
            
                const-string v4, "application/json"
            
                invoke-virtual {v11, v4}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            
                move-result v5
            
                const-string v6, "text"
            
                if-nez v5, :cond_2f
            
                invoke-virtual {v11, v6}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            
                move-result v5
            
                if-eqz v5, :cond_ab
            
                :cond_2f
                iget-object v12, v2, $requestBodyField
            
                invoke-virtual {v2, v3}, $requestHeaderMethod
            
                move-result-object v3
            
                if-nez v3, :cond_48
            
                if-eqz v12, :cond_48
            
                invoke-virtual {v12}, $requestBodyTypeMethod
            
                move-result-object v5
            
                if-eqz v5, :cond_44
            
                invoke-virtual {v5}, Ljava/lang/Object;->toString()Ljava/lang/String;
            
                move-result-object v7
            
                goto :goto_45
            
                :cond_44
                const/4 v7, 0x0
            
                :goto_45
                move-object v3, v7
            
                move-object v13, v3
            
                goto :goto_49
            
                :cond_48
                move-object v13, v3
            
                :goto_49
                const-string v14, "Content-Encoding"
            
                invoke-virtual {v2, v14}, $requestHeaderMethod
            
                move-result-object v15
            
                new-instance v3, $bufferClass
            
                invoke-direct {v3}, $bufferClass-><init>()V
            
                move-object v8, v3
            
                if-eqz v12, :cond_73
            
                if-eqz v13, :cond_73
            
                const-string v3, "application/x-www-form-urlencoded"
            
                invoke-virtual {v13, v3}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            
                move-result v3
            
                if-nez v3, :cond_70
            
                invoke-virtual {v13, v4}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            
                move-result v3
            
                if-nez v3, :cond_70
            
                invoke-virtual {v13, v6}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            
                move-result v3
            
                if-eqz v3, :cond_73
            
                :cond_70
                invoke-virtual {v12, v8}, $writeToMethod
            
                :cond_73
                invoke-virtual {v8}, $bufferInStreamMethod
            
                move-result-object v16
            
                invoke-virtual {v0, v14}, $responseHeaderMethod
            
                move-result-object v17
            
                invoke-virtual {v1}, $bodyStreamMethod
            
                move-result-object v18
            
                move-object v3, v10
            
                move v4, v9
            
                move-object v5, v15
            
                move-object/from16 v6, v16
            
                move-object/from16 v7, v17
            
                move-object/from16 v19, v8
            
                move-object/from16 v8, v18
            
                invoke-static/range {v3 .. v8}, Lapp/revanced/bilibili/patches/okhttp/OkHttpPatch;->hook(Ljava/lang/String;ILjava/lang/String;Ljava/io/InputStream;Ljava/lang/String;Ljava/io/InputStream;)Ljava/lang/String;
            
                move-result-object v3
            
                invoke-static {v11}, $mediaTypeGetMethod
            
                move-result-object v4
            
                invoke-static {v4, v3}, $createMethod
            
                move-result-object v4
            
                const/16 v5, 0xc8
            
                iput v5, v0, $codeField
            
                iput-object v4, v0, $responseBodyField
            
                new-instance v5, $headersClass
            
                iget-object v6, v0, $responseHeadersField
            
                iget-object v6, v6, $headersValueField
            
                invoke-static {v6, v14}, $removeHeaderMethod
            
                move-result-object v6
            
                invoke-direct {v5, v6}, $headersConstructor
            
                iput-object v5, v0, $responseHeadersField
            
                :cond_ab
                return-void
            """.trimIndent()
            )
        }.also { realCallClass.methods.add(it) }
        realCallGetMethod.cloneMutable(registerCount = 2, clearImplementation = true).apply {
            realCallGetMethod.name += "_Origin"
            addInstructions(
                """
                invoke-virtual {p0}, $realCallGetMethod
                move-result-object v0
                invoke-static {v0}, $hookMethod
                return-object v0
            """.trimIndent()
            )
        }.also { realCallClass.methods.add(it) }
    }
}
