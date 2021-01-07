package wave.blog.samplerecorder.client

import io.wavebeans.lib.*
import io.wavebeans.lib.io.ByteArrayLittleEndianDecoder
import java.io.Closeable
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.TargetDataLine

class CaptureLineFn(initParameters: FnInitParameters) : Fn<Pair<Long, Float>, SampleVector?>(initParameters),
    Closeable {

    constructor(
        sampleRate: Float,
        bitDepth: BitDepth,
        deviceName: String
    ) : this(
        FnInitParameters()
            .add("sampleRate", sampleRate)
            .addObj("bitDepth", bitDepth) { it.bits.toString() }
            .add("deviceName", deviceName)
    )

    private val sampleRate by lazy { initParameters.float("sampleRate") }

    private val bitDepth by lazy { initParameters.obj("bitDepth") { BitDepth.of(it.toInt()) } }

    private val deviceName by lazy { initParameters.string("deviceName") }

    private val mixer = AudioSystem.getMixer(
        AudioSystem.getMixerInfo().first {
            it.name == deviceName && AudioSystem.getMixer(it).targetLineInfo.isNotEmpty()
        }
    )

    private val decoder = ByteArrayLittleEndianDecoder(bitDepth)

    private val line: TargetDataLine by lazy {
        println(
            "Mixer: ${mixer.mixerInfo.name} (${mixer.mixerInfo.vendor}) v.${mixer.mixerInfo.version}: ${mixer.mixerInfo.description}\n" +
                    "\tSources: ${mixer.sourceLineInfo.contentToString()}\n" +
                    "\tTargets: ${mixer.targetLineInfo.contentToString()}\n"
        )

        val format = AudioFormat(sampleRate, bitDepth.bits, 1, true, false)
        println("Mixer controls: ${mixer.controls.contentToString()}")

        val info = mixer.targetLineInfo.first()

        if (!AudioSystem.isLineSupported(info)) {
            println("$info is not supported")
        }

        println("Info $info chosen")

        val line = AudioSystem.getLine(info) as TargetDataLine
        line.open(format, 512 * 1024)
        println("Line $line obtained")

        line.start()
        println("Line $line started")
        line
    }

    override fun apply(argument: Pair<Long, Float>): SampleVector? {
        val buffer = ByteArray(4096 * bitDepth.bytesPerSample)
        var readBytes: Int
        val start = System.currentTimeMillis()
        do {
            readBytes = line.read(buffer, 0, buffer.size)
            if (System.currentTimeMillis() - start > 5000) {
                println("<<TIMEOUT>>")
                break
            }
        } while (readBytes <= 0)
        return if (readBytes > 0) {
            val indices = 0 until readBytes
            val i = decoder.sequence(buffer.sliceArray(indices)).iterator()
            sampleVectorOf(readBytes / bitDepth.bytesPerSample) { _, _ -> i.next() }
        } else {
            println("Line is over. Closing...")
            null
        }
    }

    override fun close() {
        line.close()
    }
}