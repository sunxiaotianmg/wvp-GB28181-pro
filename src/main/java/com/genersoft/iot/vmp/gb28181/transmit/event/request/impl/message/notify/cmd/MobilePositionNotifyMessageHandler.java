package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.notify.cmd;

import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.SIPRequestProcessorParent;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.IMessageHandler;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.notify.NotifyMessageHandler;
import com.genersoft.iot.vmp.gb28181.utils.NumericUtil;
import com.genersoft.iot.vmp.service.IDeviceChannelService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorage;
import com.genersoft.iot.vmp.utils.DateUtil;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.SipException;
import javax.sip.message.Response;
import java.text.ParseException;

import static com.genersoft.iot.vmp.gb28181.utils.XmlUtil.getText;

/**
 * 移动设备位置数据通知，设备主动发起，不需要上级订阅
 */
@Component
public class MobilePositionNotifyMessageHandler extends SIPRequestProcessorParent implements InitializingBean, IMessageHandler {

    private Logger logger = LoggerFactory.getLogger(MobilePositionNotifyMessageHandler.class);
    private final String cmdType = "MobilePosition";

    @Autowired
    private NotifyMessageHandler notifyMessageHandler;

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private IVideoManagerStorage storager;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    @Autowired
    private IDeviceChannelService deviceChannelService;

    @Override
    public void afterPropertiesSet() throws Exception {
        notifyMessageHandler.addHandler(cmdType, this);
    }

    @Override
    public void handForDevice(RequestEvent evt, Device device, Element rootElement) {

        try {
            rootElement = getRootElement(evt, device.getCharset());
            if (rootElement == null) {
                logger.warn("[ 移动设备位置数据通知 ] content cannot be null, {}", evt.getRequest());
                responseAck(evt, Response.BAD_REQUEST);
                return;
            }
            MobilePosition mobilePosition = new MobilePosition();
            mobilePosition.setCreateTime(DateUtil.getNow());
            if (!ObjectUtils.isEmpty(device.getName())) {
                mobilePosition.setDeviceName(device.getName());
            }
            mobilePosition.setDeviceId(device.getDeviceId());
            mobilePosition.setChannelId(getText(rootElement, "DeviceID"));
            mobilePosition.setTime(getText(rootElement, "Time"));
            mobilePosition.setLongitude(Double.parseDouble(getText(rootElement, "Longitude")));
            mobilePosition.setLatitude(Double.parseDouble(getText(rootElement, "Latitude")));
            if (NumericUtil.isDouble(getText(rootElement, "Speed"))) {
                mobilePosition.setSpeed(Double.parseDouble(getText(rootElement, "Speed")));
            } else {
                mobilePosition.setSpeed(0.0);
            }
            if (NumericUtil.isDouble(getText(rootElement, "Direction"))) {
                mobilePosition.setDirection(Double.parseDouble(getText(rootElement, "Direction")));
            } else {
                mobilePosition.setDirection(0.0);
            }
            if (NumericUtil.isDouble(getText(rootElement, "Altitude"))) {
                mobilePosition.setAltitude(Double.parseDouble(getText(rootElement, "Altitude")));
            } else {
                mobilePosition.setAltitude(0.0);
            }
            mobilePosition.setReportSource("Mobile Position");


            // 更新device channel 的经纬度
            DeviceChannel deviceChannel = new DeviceChannel();
            deviceChannel.setDeviceId(device.getDeviceId());
            deviceChannel.setChannelId(mobilePosition.getChannelId());
            deviceChannel.setLongitude(mobilePosition.getLongitude());
            deviceChannel.setLatitude(mobilePosition.getLatitude());
            deviceChannel.setGpsTime(mobilePosition.getTime());

            deviceChannel = deviceChannelService.updateGps(deviceChannel, device);

            mobilePosition.setLongitudeWgs84(deviceChannel.getLongitudeWgs84());
            mobilePosition.setLatitudeWgs84(deviceChannel.getLatitudeWgs84());
            mobilePosition.setLongitudeGcj02(deviceChannel.getLongitudeGcj02());
            mobilePosition.setLatitudeGcj02(deviceChannel.getLatitudeGcj02());

            if (userSetting.getSavePositionHistory()) {
                storager.insertMobilePosition(mobilePosition);
            }
            storager.updateChannelPosition(deviceChannel);
            //回复 200 OK
            responseAck(evt, Response.OK);

            // 发送redis消息。 通知位置信息的变化
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("time", mobilePosition.getTime());
            jsonObject.put("serial", deviceChannel.getDeviceId());
            jsonObject.put("code", deviceChannel.getChannelId());
            jsonObject.put("longitude", mobilePosition.getLongitude());
            jsonObject.put("latitude", mobilePosition.getLatitude());
            jsonObject.put("altitude", mobilePosition.getAltitude());
            jsonObject.put("direction", mobilePosition.getDirection());
            jsonObject.put("speed", mobilePosition.getSpeed());
            redisCatchStorage.sendMobilePositionMsg(jsonObject);

        } catch (DocumentException | SipException | InvalidArgumentException | ParseException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void handForPlatform(RequestEvent evt, ParentPlatform parentPlatform, Element element) {

    }
}
