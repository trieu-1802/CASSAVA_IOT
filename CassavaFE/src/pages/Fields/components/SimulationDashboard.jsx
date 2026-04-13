import React, { useEffect, useState } from 'react';
import { Typography, Spin, Button, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import api from '../../../services/api';
import YieldTab from '../FieldDetail/YieldTab'; // Import file biểu đồ vừa tạo

const { Title } = Typography;

const SimulationDashboard = ({ fieldId, fieldName }) => {
    const [chartData, setChartData] = useState([]);
    const [loading, setLoading] = useState(false);

    /**const fetchChartData = async () => {
        setLoading(true);
        try {
            const response = await api.get(`/simulation/chart`, {
                params: { fieldId: fieldId }
            });
            
            const { day, yield: yields, irrigation, leafArea, labels } = response.data;
            
            // Format dữ liệu từ mảng lẻ thành mảng object cho Recharts
            const formattedData = day.map((d, index) => ({
                time: new Date(d).toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' }),
                fullTime: d,
                yield: yields[index],
                irrigation: irrigation[index],
                leafArea: leafArea[index],
                labileCarbon: labels[index]
            }));
            
            setChartData(formattedData);
        } catch (error) {
            message.error("Không thể tải dữ liệu mô phỏng");
        } finally {
            setLoading(false);
        }
    };
    */
    const fetchChartData = async () => {
     console.log("Đang gọi API cho fieldId:", fieldId); // THÊM DÒNG NÀY
     setLoading(true);
    try {
        const response = await api.get(`/simulation/chart`, {
            params: { fieldId: fieldId }
        });
        
        const { day, yield: yields, irrigation, leafArea, labels } = response.data;
        
    const formattedData = day.map((d, index) => {
    // d có dạng: "Thu Mar 16 00:00:00 ICT 2023"
    // Để an toàn nhất, ta sẽ tách chuỗi để lấy Ngày, Tháng, Năm
    const parts = d.split(' ');
    // parts[1] là Tháng (Mar), parts[2] là Ngày (16), parts[5] là Năm (2023)
    
    const monthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    const monthIndex = monthNames.indexOf(parts[1]);
    const dayNum = parseInt(parts[2]);
    const yearNum = parseInt(parts[5]);

    // Tạo đối tượng Date mới từ các thành phần đã tách
    const dateObj = new Date(yearNum, monthIndex, dayNum);
    
    return {
        // Hiển thị dạng 16/03
        time: isNaN(dateObj.getTime()) ? "Lỗi" : dateObj.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' }),
        fullTime: d,
        yield: yields[index] || 0,
        irrigation: irrigation[index] || 0,
        leafArea: leafArea[index] || 0,
        labileCarbon: labels[index] || 0
    };
    });
        
        setChartData(formattedData);
    } catch (error) {
        console.error("Error details:", error);
        message.error("Không thể tải dữ liệu mô phỏng");
    } finally {
        setLoading(false);
    }
};

    useEffect(() => {
        if (fieldId) fetchChartData();
    }, [fieldId]);

    return (
        <div style={{ padding: '24px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
                <Title level={3} style={{ margin: 0 }}>Kết quả mô phỏng: {fieldName || fieldId}</Title>
                <Button icon={<ReloadOutlined />} onClick={fetchChartData} loading={loading}>
                    Làm mới dữ liệu
                </Button>
            </div>

            {loading ? (
                <div style={{ textAlign: 'center', padding: '100px' }}>
                    <Spin size="large" tip="Đang tải dữ liệu biểu đồ..." />
                </div>
            ) : (
                <YieldTab data={chartData} />
            )}
        </div>
    );
};

export default SimulationDashboard;