/*
    SMS Library for the Java platform
    Copyright (C) 2002  Markus Eriksson

    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Lesser General Public
    License as published by the Free Software Foundation; either
    version 2.1 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public
    License along with this library; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package org.marre.sms.transport.gsm;

import java.util.*;
import java.io.*;

import org.marre.sms.util.SmsPduUtil;
import org.marre.sms.transport.SmsTransport;
import org.marre.sms.SmsPdu;
import org.marre.sms.SmsAddress;
import org.marre.sms.SmsException;
import org.marre.sms.SmsConstants;

/**
 * An SmsTransport that sends the SMS from an GSM phone that is attached
 * to the serial port.
 * <p>
 * This transport has the following settable parameters:
 * <br>
 * <pre>
 * <b>sms.gsm.serialport</b> - Serial port where the GSM phone is located. Ex: "COM1"
 * </pre>
 * <p>
 * <i>This transport cannot set the sending "address" to anything else
 * than the sending phone's phonenumber.</i>
 *
 * @todo Validity period
 * @todo Clean up the support for messages with UDH
 *
 * @author Markus Eriksson
 * @version 1.0
 */
public class GsmTransport implements SmsTransport
{
    public GsmTransport()
    {
    }

    public void init(Properties theProps)
        throws SmsException
    {
    }

    /**
     * Initializes the communication with the GSM phone.
     *
     * @throws SmsException Thrown when the transport fails to communicate
     * with the GSM phone
     */
    public void connect()
        throws SmsException
    {
        // Connect serial port
    }

    /**
     * Sends the SMS message to the given recipients.
     * <p>
     * Note: The sending address is ignored for the GSM transport.
     *
     * @param thePdu The message pdu to send
     * @param theDestination The reciever
     * @param theSender The sending address, ignored
     * @throws SmsException Thrown if we fail to send the SMS
     */
    public void send(SmsPdu thePdu, SmsAddress theDestination, SmsAddress theSender)
        throws SmsException
    {
        byte ud[] = thePdu.getUserData();
        byte udh[] = thePdu.getUserDataHeader();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(161);

        if (theDestination.getTypeOfNumber() == SmsConstants.TON_ALPHANUMERIC)
        {
            throw new SmsException("Cannot sent SMS to an ALPHANUMERIC address");
        }

        try
        {
            int nUdhBits = 0;
            int nUdBits = 0;

            // udLength can be in septets or octets
            int udLength = 0;

            int nUdhBytes = 0;
            int nFillBits = 0;

            int tpUdl = 0;

            int nTotalBytes = 0;

            // Use default SMSC
            baos.write(0x00);

            // UDH?
            if( (udh == null) || (udh.length == 0) )
            {
                // TP-Message-Type-Indicator = SUBMIT
                // TP-Reject-Duplicates = ON
                // TP-Validity-Period-Format = No field
                // TP-Status-Report-Request = No
                // TP-User-Data-Header = No
                // TP-Reply-Path = No
                baos.write(0x01);
            }
            else
            {
                nUdhBytes = udh.length;

                // +1 is for the size header...
                nUdhBits = (nUdhBytes + 1) * 8;

                // TP-Message-Type-Indicator = SUBMIT
                // TP-Reject-Duplicates = ON
                // TP-Validity-Period-Format = No field
                // TP-Status-Report-Request = No
                // TP-User-Data-Header = Yes
                // TP-Reply-Path = No
                baos.write(0x41);
            }

            if (thePdu.getDataCodingScheme() == 0x00)
            {
                // GSM CHARSET

                // UD length in septets
                udLength = (ud.length * 8) / 7;

                int nUdhSeptets = nUdhBits / 7;
                if ( (nUdhBits % 7) > 0)
                {
                    nUdhSeptets += 1;
                }
                int nTotalBits = udLength * 7 + nFillBits + udh.length * 8;

                tpUdl = udLength + nUdhSeptets;
                nUdBits = udLength * 7;
                nFillBits = 7 - (nUdhBits % 7);

                nTotalBytes = nTotalBits / 8;
                if (nTotalBits % 8 > 0)
                {
                    nTotalBytes += 1;
                }
            }
            else if (thePdu.getDataCodingScheme() == 0x04)
            {
                // 8BIT
                udLength = ud.length;
                // +1 For the UDH Length
                tpUdl = ud.length + nUdhBytes + 1;
                nUdBits = udLength * 8;
                nFillBits = 0;
                nTotalBytes = tpUdl - 1;
            }
            else if (thePdu.getDataCodingScheme() == 0x08)
            {
                // UCS2...
                udLength = ud.length;
                // +1 For the UDH Length
                tpUdl = ud.length + nUdhBytes + 1;
                nUdBits = udLength * 8;
                nFillBits = 0;
                nTotalBytes = tpUdl - 1;
            }

            // TP-Message-Reference
            // Leave to 0x00, MS will set it
            baos.write(0x00);

            // 2-12 octets
            // TP-DA
            // - 1:st octet - length of address (4 bits)
            // - 2:nd octet
            //   - bit 7 - always 1
            //   - bit 4-6 - TON
            //   - bit 0-3 - NPI
            // - n octets - BCD
            // 1010 - "*"
            // 1011 - "#"
            // 1100 - "a"
            // 1101 - "b"
            // 1110 - "c"
            writeDestinationAddress(baos, theDestination);

            // 1 octet
            // TP-PID
            // Probably 0x00
            baos.write(0x00);

            // 1 Integer
            // TP-DCS
            // UCS, septets, language, SMS class...
            baos.write(thePdu.getDataCodingScheme());

            // 1 octet/ 7 octets
            // TP-VP - Optional
            // Probably not needed

            // UDH?
            if( (udh == null) || (udh.length == 0) )
            {
                // 1 Integer
                // TP-UDL
                // UDL includes the length of UDH
                baos.write(udLength);

                // n octets
                // TP-UD
                baos.write(ud);
            }
            else
            {
                // 1 Integer
                // TP-UDL
                // UDL includes the length of UDH
                baos.write(tpUdl);

                // User Data header length
                // In octets minus eventual fill bits
                baos.write(nUdhBytes);

                // The whole UD PDU
                byte fullUd[] = new byte[139];

                // n octets
                // TP-UDH
                System.arraycopy(udh, 0, fullUd, 0, nUdhBytes);

                // n octets
                // TP-UD
                SmsPduUtil.arrayCopy(ud, 0, fullUd, nUdhBytes, nFillBits, nUdBits);

                baos.write(fullUd, 0, nTotalBytes);
            }
            baos.close();
        }
        catch (IOException ex)
        {
            throw new SmsException(ex.getMessage());
        }
        System.out.println("PDU : " + SmsPduUtil.bytesToHexString(baos.toByteArray()));
        System.out.println("Length : " + baos.size());
    }

    public void send(SmsPdu thePdus[], SmsAddress theDestination, SmsAddress theSender)
        throws SmsException
    {
        for(int i=0; i < thePdus.length; i++)
        {
            send(thePdus[i], theDestination, theSender);
        }
    }


    /**
     * Sends a "AT" command to keep the connection alive
     *
     * @throws SmsException
     */
    public void ping()
        throws SmsException
    {
        // PONG
    }

    /**
     * Closes the serial connection to the phone
     *
     * @throws SmsException
     */
    public void disconnect()
        throws SmsException
    {
        // disconnect
    }

    /**
     * Writes a destination address to the given stream in the correct format
     *
     * @param theOs Stream to write to
     * @param theDestination Destination address to encode
     * @throws IOException Thrown if failing to write to the stream
     */
    private void writeDestinationAddress(OutputStream theOs, SmsAddress theDestination)
        throws IOException
    {
        String address = theDestination.getAddress();
        int ton = theDestination.getTypeOfNumber();
        int npi = theDestination.getNumberingPlanIdentification();

        // trim leading + from address
        if (address.charAt(0) == '+')
        {
            address = address.substring(1);
        }

        // Length in semi octets
        theOs.write(address.length());

        // Type Of Address
        theOs.write(0x80 | ton << 4 | npi);

        // BCD encode
        SmsPduUtil.writeBcdNumber(theOs, address);
    }
}
