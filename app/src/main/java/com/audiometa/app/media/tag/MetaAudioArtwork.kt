package com.audiometa.app.media.tag

import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.images.Artwork
import java.io.File

/**
 * 自己的 Artwork 实现。
 *
 * 为什么不能直接用 ArtworkFactory.getNew()：
 * - StandardArtwork.setImageFromData() 会走 javax.imageio.ImageIO —— Android 上没有这个类；
 * - AndroidArtwork.setImageFromData() 是个没写完的桩，直接 throw UnsupportedOperationException()，
 *   而且**没有 message**，所以上层只能看到「写入失败」这种没信息量的报错。
 *
 * 而 FlacTag.createField(Artwork) 对非链接图会**无条件**调用 setImageFromData()，
 * 于是写 FLAC 封面必然失败（Vorbis/OGG 同理）。
 *
 * 这里直接返回 true：图片二进制本来就已就绪，不需要框架再去解码一遍。
 */
class AudioMetaArtwork(
    private var data: ByteArray = ByteArray(0),
    private var mime: String = "image/jpeg",
    private var description: String = "",
    private var pictureType: Int = 3,
    private var width: Int = 0,
    private var height: Int = 0
) : Artwork {

    override fun getBinaryData(): ByteArray = data

    override fun setBinaryData(binaryData: ByteArray?) {
        data = binaryData ?: ByteArray(0)
    }

    override fun getMimeType(): String = mime

    override fun setMimeType(mimeType: String?) {
        mime = mimeType.orEmpty()
    }

    override fun getDescription(): String = description

    override fun setDescription(desc: String?) {
        description = desc.orEmpty()
    }

    override fun getWidth(): Int = width

    override fun setWidth(value: Int) {
        width = value
    }

    override fun getHeight(): Int = height

    override fun setHeight(value: Int) {
        height = value
    }

    override fun getPictureType(): Int = pictureType

    override fun setPictureType(type: Int) {
        pictureType = type
    }

    override fun isLinked(): Boolean = false

    override fun setLinked(linked: Boolean) = Unit

    override fun getImageUrl(): String = ""

    override fun setImageUrl(url: String?) = Unit

    /** 二进制数据已就绪，告诉框架不必再解码。 */
    override fun setImageFromData(): Boolean = data.isNotEmpty()

    /** 不提供 java.awt 图像。各格式的 createField 只取 binaryData，不会用到这里。 */
    override fun getImage(): Any? = null

    override fun setFromFile(file: File) {
        data = file.readBytes()
        if (mime.isBlank()) mime = "image/jpeg"
    }

    override fun setFromMetadataBlockDataPicture(picture: MetadataBlockDataPicture) {
        data = picture.imageData ?: ByteArray(0)
        mime = picture.mimeType.orEmpty()
        description = picture.description.orEmpty()
        pictureType = picture.pictureType
        width = picture.width
        height = picture.height
    }
}
