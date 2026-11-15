package app.rustmfa.vault

import java.io.DataInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's minSdk is 28, but these tests run on a desktop JDK, which has JDK members that Android added later
 * (or never). A call to one compiles and passes here, then throws NoSuchMethodError on an older phone. This test
 * reads the compiled `:vault` main classes and fails if any of them references a banned member.
 */
class AndroidApiLevelTest {
    /**
     * JDK members that exist on the build JDK but not on Android API 28 (minSdk) and that core library
     * desugaring does not backport. Format: `owner.name:descriptor`, as in the class file.
     * - `ByteArrayOutputStream.toString(Charset)`: Android API 33. Kotlin resolves `out.toString(Charsets.UTF_8)`
     *   to this JDK member instead of the stdlib extension.
     */
    private val bannedMembers = setOf(
        "java/io/ByteArrayOutputStream.toString:(Ljava/nio/charset/Charset;)Ljava/lang/String;",
    )

    private fun mainClassFiles(): List<File> {
        val root = File(OtpAuthUri::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(root.isDirectory, "expected the :vault main classes as a directory, got $root")
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
    }

    @Test
    fun mainClassesReferenceNoBannedJdkMember() {
        val files = mainClassFiles()
        assertTrue(files.any { it.name == "OtpAuthUri.class" }, "the scan did not find OtpAuthUri.class")
        val hits = files.flatMap { file -> memberRefs(file).filter { it in bannedMembers }.map { "${file.name}: $it" } }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun theScanSeesMemberReferences() {
        // Guards the parser: a scan that returns nothing would pass the test above vacuously.
        val refs = mainClassFiles().flatMap(::memberRefs).toSet()
        assertTrue("java/lang/Character.toChars:(I)[C" in refs, "parser found no known reference")
    }

    companion object {
        /** Every Methodref, InterfaceMethodref and Fieldref in the constant pool, as `owner.name:descriptor`. */
        fun memberRefs(file: File): List<String> = DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file: $file" }
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classIndex = IntArray(count)
            val refs = mutableListOf<Pair<Int, Int>>()
            val nameAndType = HashMap<Int, Pair<Int, Int>>()
            var i = 1
            while (i < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[i] = input.readUTF()
                    3, 4 -> input.readInt()
                    5, 6 -> { input.readLong(); i++ }
                    7 -> classIndex[i] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.readUnsignedShort()
                    9, 10, 11 -> refs += input.readUnsignedShort() to input.readUnsignedShort()
                    12 -> nameAndType[i] = input.readUnsignedShort() to input.readUnsignedShort()
                    15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                    17, 18 -> input.readInt()
                    else -> error("unknown constant pool tag $tag in $file")
                }
                i++
            }
            refs.map { (cls, nt) ->
                val (name, type) = nameAndType.getValue(nt)
                "${utf8[classIndex[cls]]}.${utf8[name]}:${utf8[type]}"
            }
        }
    }
}
