package io.spicelabs.cilantro.metadata

import io.spicelabs.dotnet_support.Guid

class GuidHeap(data: Array[Byte]) extends Heap(data) {
    def read(index: Int) =
        val guid_size = 16
        if (index == 0 || ((index - 1) + guid_size) > data.length)
            Guid.empty // this might not be right
        val buffer = Array.ofDim[Byte](guid_size)
        Array.copy(data, index - 1, buffer, 0, guid_size)
        Guid(buffer)
}
