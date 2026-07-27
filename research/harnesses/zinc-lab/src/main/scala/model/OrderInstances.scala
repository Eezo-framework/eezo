package model

// The scala3#22475 shape: derived instance in a DIFFERENT file from the case class
given orderCodec: Codec[Order] = Codec.derived
given orderTable: Table[Order] = Table.derived
