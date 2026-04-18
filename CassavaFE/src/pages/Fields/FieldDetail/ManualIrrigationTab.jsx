/**import React, { useState, useEffect, useRef } from 'react';
import { Form, InputNumber, Card, Statistic, Row, Col, Divider, Button, Space, Progress, message, Modal } from 'antd';
import { PlayCircleOutlined, StopOutlined, ClockCircleOutlined, ExclamationCircleOutlined } from '@ant-design/icons';

// QUAN TRỌNG: Phải khai báo confirm từ Modal của Ant Design
const { confirm } = Modal;

const ManualIrrigationTab = () => {
  // --- States cho cài đặt ---
  const [timeSetting, setTimeSetting] = useState(10); // Phút
  const [flowRate, setFlowRate] = useState(1.5);    // m3/h

  // --- States cho vận hành ---
  const [isActive, setIsActive] = useState(false);
  const [timeLeft, setTimeLeft] = useState(0); // Giây
  const timerRef = useRef(null);

  // Tính toán lượng nước dựa trên số phút
  const calculateWater = (mins) => ((flowRate / 60) * mins).toFixed(2);

  // Xử lý đếm ngược
  useEffect(() => {
    if (isActive && timeLeft > 0) {
      timerRef.current = setInterval(() => {
        setTimeLeft((prev) => prev - 1);
      }, 1000);
    } else if (timeLeft === 0 && isActive) {
      handleStop();
      message.success("Hệ thống đã tự động ngắt sau khi hoàn thành chu kỳ!");
    }

    return () => clearInterval(timerRef.current);
  }, [isActive, timeLeft]);

  const handleStart = () => {
    setTimeLeft(timeSetting * 60);
    setIsActive(true);
    message.success("Đã khởi động hệ thống tưới tay");
  };

  const handleStop = () => {
    setIsActive(false);
    setTimeLeft(0);
    if (timerRef.current) clearInterval(timerRef.current);
    message.warning("Hệ thống đã dừng tất cả hoạt động");
  };

  // Pop-up xác nhận trước khi chạy
  const showConfirmStart = () => {
    if (!timeSetting || timeSetting <= 0) {
      return message.error("Vui lòng thiết lập thời gian tưới!");
    }

    confirm({
      title: 'Xác nhận kích hoạt tưới tay?',
      icon: <ExclamationCircleOutlined />,
      content: (
        <div>
          <p>Hệ thống sẽ chạy với các thông số sau:</p>
          <ul>
            <li>Thời gian: <b>{timeSetting} phút</b></li>
            <li>Lưu lượng: <b>{flowRate} m³/h</b></li>
            <li>Tổng lượng dự kiến: <b>{calculateWater(timeSetting)} m³</b></li>
          </ul>
        </div>
      ),
      okText: 'Xác nhận chạy',
      okType: 'primary',
      cancelText: 'Hủy bỏ',
      onOk() {
        handleStart();
      },
    });
  };

  const formatTime = (seconds) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
  };

  // Tính % tiến độ
  const progressPercent = isActive 
    ? Math.round(((timeSetting * 60 - timeLeft) / (timeSetting * 60)) * 100) 
    : 0;

  // Tính lượng nước đã tiêu thụ thực tế khi đang chạy
  const waterConsumed = isActive 
    ? calculateWater((timeSetting * 60 - timeLeft) / 60)
    : "0.00";

  return (
    <div style={{ padding: '20px 0' }}>
      <Row gutter={[24, 24]}>
        {/* Cột cài đặt 
        <Col xs={24} md={10}>
          <Card title={<span><ClockCircleOutlined /> Cấu hình hẹn giờ</span>} bordered={false}>
            <Form layout="vertical">
              <Form.Item label="Thời gian chạy (phút)">
                <Space wrap>
                  <InputNumber 
                    min={1} 
                    value={timeSetting} 
                    onChange={setTimeSetting} 
                    disabled={isActive}
                    style={{ width: '100px' }}
                  />
                  <Button onClick={() => setTimeSetting(10)} disabled={isActive}>10p</Button>
                  <Button onClick={() => setTimeSetting(30)} disabled={isActive}>30p</Button>
                  <Button onClick={() => setTimeSetting(60)} disabled={isActive}>1h</Button>
                </Space>
              </Form.Item>

              <Form.Item label="Lưu lượng nước hiện tại (m³/h)">
                <InputNumber 
                  min={0.1} 
                  step={0.1}
                  value={flowRate} 
                  onChange={setFlowRate} 
                  style={{ width: '100%' }}
                />
              </Form.Item>

              <Divider />

              {!isActive ? (
                <Button 
                  type="primary" 
                  icon={<PlayCircleOutlined />} 
                  size="large" 
                  onClick={showConfirmStart}
                  block
                >
                  Bắt đầu tưới
                </Button>
              ) : (
                <Button 
                  danger 
                  type="primary" 
                  icon={<StopOutlined />} 
                  size="large" 
                  onClick={handleStop}
                  block
                >
                  Dừng tất cả ngay lập tức
                </Button>
              )}
            </Form>
          </Card>
        </Col>

        {/* Cột hiển thị trạng thái 
        <Col xs={24} md={14}>
          <Card title="Trạng thái vận hành thực tế" style={{ textAlign: 'center' }}>
            <Row gutter={16}>
              <Col span={8}>
                <Statistic 
                  title="Thời gian còn lại" 
                  value={isActive ? formatTime(timeLeft) : "00:00"} 
                  valueStyle={{ color: isActive ? '#1890ff' : '#d9d9d9' }}
                />
              </Col>
              <Col span={8}>
                <Statistic 
                  title="Đã tưới thực tế" 
                  value={waterConsumed} 
                  suffix="m³" 
                  precision={2}
                  valueStyle={{ color: '#52c41a' }}
                />
              </Col>
              <Col span={8}>
                <Statistic 
                  title="Lưu lượng" 
                  value={flowRate} 
                  suffix="m³/h" 
                />
              </Col>
            </Row>

            <div style={{ marginTop: '40px' }}>
              <Progress 
                type="dashboard" 
                percent={progressPercent} 
                status={isActive ? "active" : "normal"}
                strokeColor={isActive ? { '0%': '#108ee9', '100%': '#87d068' } : '#d9d9d9'}
              />
              <div style={{ marginTop: 10 }}>
                <strong style={{ fontSize: '16px' }}>
                  {isActive ? "HỆ THỐNG ĐANG HOẠT ĐỘNG" : "HỆ THỐNG ĐANG DỪNG"}
                </strong>
                <p style={{ color: '#8c8c8c' }}>
                  Tổng lượng nước mục tiêu: {calculateWater(timeSetting)} m³
                </p>
              </div>
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default ManualIrrigationTab;
*/

import React, { useState, useEffect, useRef } from 'react';
import { Form, InputNumber, Card, Statistic, Row, Col, Divider, Button, Space, Progress, message, Modal } from 'antd';
import { PlayCircleOutlined, StopOutlined, ClockCircleOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import axios from 'axios';

const { confirm } = Modal;

const ManualIrrigationTab = ({ fieldId }) => {
  const [timeSetting, setTimeSetting] = useState(10);
  const [isActive, setIsActive] = useState(false);
  const [timeLeft, setTimeLeft] = useState(0); 
  const [loading, setLoading] = useState(false);
  const timerRef = useRef(null);

  // KEY: Tên biến lưu trong localStorage theo từng fieldId
  const STORAGE_KEY = `irrigation_endtime_${fieldId}`;

  // 1. Kiểm tra trạng thái khi lần đầu load Component hoặc chuyển Tab lại
  useEffect(() => {
    const checkStatus = () => {
      const savedEndTime = localStorage.getItem(STORAGE_KEY);
      if (savedEndTime) {
        const remaining = Math.floor((parseInt(savedEndTime) - Date.now()) / 1000);
        if (remaining > 0) {
          setIsActive(true);
          setTimeLeft(remaining);
        } else {
          // Đã hết thời gian khi đang vắng mặt
          handleFinish();
        }
      }
    };
    checkStatus();
  }, [fieldId]);

  // 2. Logic đếm ngược (chỉ để cập nhật giao diện mỗi giây)
  useEffect(() => {
    if (isActive && timeLeft > 0) {
      timerRef.current = setInterval(() => {
        const savedEndTime = localStorage.getItem(STORAGE_KEY);
        if (savedEndTime) {
          const remaining = Math.floor((parseInt(savedEndTime) - Date.now()) / 1000);
          if (remaining <= 0) {
            handleFinish();
          } else {
            setTimeLeft(remaining);
          }
        }
      }, 1000);
    }
    return () => clearInterval(timerRef.current);
  }, [isActive, timeLeft]);

  const handleFinish = () => {
    setIsActive(false);
    setTimeLeft(0);
    localStorage.removeItem(STORAGE_KEY);
    if (timerRef.current) clearInterval(timerRef.current);
  };

  const handleStart = async () => {
    setLoading(true);
    try {
      const response = await axios.post(`http://localhost:8081/mongo/field/manual`, null, {
        params: { fieldId: fieldId, duration: timeSetting }
      });

      if (response.data.status === "success") {
        // Tính toán EndTime và lưu vào LocalStorage
        const endTime = Date.now() + timeSetting * 60 * 1000;
        localStorage.setItem(STORAGE_KEY, endTime.toString());
        
        setTimeLeft(timeSetting * 60);
        setIsActive(true);
        message.success("Lệnh tưới đã được kích hoạt!");
      }
    } catch (error) {
      message.error("Lỗi kết nối Backend!");
    } finally {
      setLoading(false);
    }
  };

  const handleStop = () => {
    handleFinish();
    message.warning("Đã dừng theo dõi (Lưu ý: Bơm thực tế có thể vẫn chạy đến hết giờ)");
  };

  const showConfirmStart = () => {
     confirm({

      title: 'Xác nhận kích hoạt tưới tay?',
      icon: <ExclamationCircleOutlined />,
      content: (
        <div>
          <p>Hệ thống sẽ bật bơm tại cánh đồng: <b>{fieldId}</b></p>
          <p>Thời gian dự kiến: <b>{timeSetting} phút</b></p>
          <p style={{ color: '#faad14' }}><i>Lưu ý: Hệ thống sẽ tự động ngắt sau khi hết giờ.</i></p>
        </div>
      ),
      okText: 'Kích hoạt ngay',
      cancelText: 'Hủy bỏ',
      confirmLoading: loading,
      onOk: handleStart,
    });
  };

  const formatTime = (seconds) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
  };

  return (
    <div style={{ padding: '20px 0' }}>
      <Row gutter={[24, 24]}>
        <Col xs={24} md={10}>
          <Card title="Thiết lập lệnh">
            <Form layout="vertical">
              <Form.Item label="Thời gian tưới (phút)">
                <InputNumber 
                  min={1} 
                  value={timeSetting} 
                  onChange={setTimeSetting} 
                  disabled={isActive}
                  style={{ width: '100%' }}
                />
              </Form.Item>
              <Divider />
              <Button 
                type="primary" 
                size="large" 
                onClick={showConfirmStart}
                block
                loading={loading}
                disabled={isActive}
              >
                {isActive ? "HỆ THỐNG ĐANG CHẠY" : "KÍCH HOẠT"}
              </Button>
              {isActive && (
                <Button danger block onClick={handleStop} style={{ marginTop: 12 }}>
                  DỪNG THEO DÕI
                </Button>
              )}
            </Form>
          </Card>
        </Col>

        <Col xs={24} md={14}>
          <Card title="Trạng thái thực tế" style={{ textAlign: 'center' }}>
            <Statistic 
              title="Thời gian còn lại" 
              value={formatTime(timeLeft)} 
              valueStyle={{ fontSize: '48px', color: isActive ? '#1890ff' : '#cfcfcf' }}
            />
            <Progress 
              type="dashboard" 
              percent={isActive ? Math.round(((timeSetting * 60 - timeLeft) / (timeSetting * 60)) * 100) : 0} 
              width={200}
              strokeColor="#52c41a"
            />
            <p style={{marginTop: 10}}>{isActive ? "ĐANG TƯỚI " : "SẴN SÀNG"}</p>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default ManualIrrigationTab;