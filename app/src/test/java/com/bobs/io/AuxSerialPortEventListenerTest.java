package com.bobs.io;

import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortException;
import org.junit.Before;
import org.junit.Test;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by dokeeffe on 1/9/17.
 */
public class AuxSerialPortEventListenerTest {

    private AuxSerialPortEventListener sut;
    private SerialPort serialPort;
    private Queue queue;
    private AtomicInteger nextExpectedCommandLength = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        serialPort = mock(SerialPort.class);
        queue = new ArrayBlockingQueue(10);
        sut = new AuxSerialPortEventListener(serialPort, queue, nextExpectedCommandLength);
    }

    @Test
    public void serialEvent_partialMessage() throws Exception {
        SerialPortEvent event = mock(SerialPortEvent.class);
        when(event.getEventValue()).thenReturn(100);
        when(event.isRXCHAR()).thenReturn(true);
        when(serialPort.readBytes(100)).thenReturn(new byte[]{0x01, 0x02});
        sut.serialEvent(event);
        assertEquals(0, queue.size());

    }

    @Test
    public void serialEvent_fullMessage() throws Exception {
        SerialPortEvent event = mock(SerialPortEvent.class);
        when(event.getEventValue()).thenReturn(100);
        when(event.isRXCHAR()).thenReturn(true);
        when(serialPort.readBytes(100)).thenReturn(new byte[]{0x01, 0x02, 0x23});
        sut.serialEvent(event);
        assertEquals(1, queue.size());
        byte[] message = (byte[]) queue.poll();
        assertEquals(2, message.length);
        assertEquals(0x01, message[0]);
        assertEquals(0x02, message[1]);

    }

    @Test
    public void serialEvent_partialMessageFollowedByEndToken() throws Exception {
        SerialPortEvent event = mock(SerialPortEvent.class);
        when(event.getEventValue()).thenReturn(100);
        when(event.isRXCHAR()).thenReturn(true);
        when(serialPort.readBytes(100)).thenReturn(new byte[]{0x01, 0x02});
        sut.serialEvent(event);
        when(serialPort.readBytes(100)).thenReturn(new byte[]{0x23});
        sut.serialEvent(event);

        assertEquals(1, queue.size());
        byte[] message = (byte[]) queue.poll();
        assertEquals(2, message.length);
        assertEquals(0x01, message[0]);
        assertEquals(0x02, message[1]);
    }

    @Test
    public void serialEvent_partialMessageThatEndsIn23FollowedByEndToken() throws Exception {
        this.nextExpectedCommandLength.set(3);
        SerialPortEvent event = mock(SerialPortEvent.class);
        when(event.getEventValue()).thenReturn(100);
        when(event.isRXCHAR()).thenReturn(true);
        when(serialPort.readBytes(100)).thenReturn(new byte[]{0x01, 0x02, 0x23});
        sut.serialEvent(event);
        when(serialPort.readBytes(100)).thenReturn(new byte[]{0x23});
        sut.serialEvent(event);

        assertEquals(1, queue.size());
        byte[] message = (byte[]) queue.poll();
        assertEquals(3, message.length);
        assertEquals(0x01, message[0]);
        assertEquals(0x02, message[1]);
        assertEquals(0x23, message[2]);
    }

    @Test
    public void serialEvent_commsErrorDoesNotThrowException() throws Exception {
        SerialPortEvent event = mock(SerialPortEvent.class);
        when(event.getEventValue()).thenReturn(100);
        when(event.isRXCHAR()).thenReturn(true);
        when(serialPort.readBytes(100)).thenThrow(new SerialPortException("", "", ""));
        sut.serialEvent(event);
    }

}